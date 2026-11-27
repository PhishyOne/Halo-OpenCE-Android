package com.halo.decomp;

import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Read-only view of the offline game's position, flight and safe-zone snapshot. */
final class RoyaleMinimap {
    static final int INACTIVE = 0, EXPLORE = 1, INSERTION = 2, HOLD = 3, CLOSE = 4, FINAL = 5;
    final float[] data = new float[25];
    private static final int CURRENT = 0xff63d6ff, NEXT = 0xfff1f5ec, YOU = 0xffffcc58;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint stormHaze = new Paint(Paint.DITHER_FLAG);
    private final Path path = new Path();
    private final RectF panel = new RectF(), map = new RectF(), dest = new RectF();
    private final DashPathEffect dash = new DashPathEffect(new float[]{5, 4}, 0);
    private final AssetManager assets;
    private boolean loaded, highlands, expanded;
    private float unit = 3.048f, centerX, centerY, scale;
    static final float COMPACT_SIZE = 116;
    private float viewWidth, viewHeight, statusLeft, statusTop;
    private Bitmap[] tiles = new Bitmap[0];
    private float[][] bounds = new float[0][];
    private String[] names = new String[0];
    private float[][] landmarks = new float[0][];
    private RectF[] labels = new RectF[0];

    RoyaleMinimap(AssetManager assets) {
        this.assets = assets;
        stormHaze.setShader(new RadialGradient(0, 0, 1,
                new int[]{0x104877ae, 0x244877ae, 0x704976b9},
                new float[]{0f, .55f, 1f}, Shader.TileMode.CLAMP));
    }

    void close() { expanded = false; }
    boolean contains(float x, float y) { return panel.contains(x, y); }
    void toggle() { expanded = !expanded; }

    void layout(float width, float height, float insetLeft, float insetTop, float insetRight) {
        viewWidth = width;
        viewHeight = height;
        statusLeft = Math.max(16, insetLeft + 6);
        statusTop = Math.max(8, insetTop + 6);
        float size = expanded ? Math.min(height * .67f, width * .65f) : COMPACT_SIZE;
        float left = expanded ? (width - size) / 2 : width - Math.max(16, insetRight + 6) - size;
        float top = expanded ? (height - size - 53) / 2 : Math.max(12, insetTop + 6);
        map.set(left, top, left + size, top + size);
        panel.set(left - 3, top - 3, left + size + 3, map.bottom + (expanded ? 28 : 3));
    }

    private void load() {
        if (loaded && highlands == (data[24] > 0)) return;
        highlands = data[24] > 0;
        loaded = true;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                assets.open("royale/atlas.json"), StandardCharsets.UTF_8))) {
            JSONObject atlas = new JSONObject(reader.readLine());
            unit = (float)atlas.getDouble("metresPerUnit");
            JSONArray images = atlas.getJSONArray("tiles"), places = atlas.getJSONArray("landmarks");
            tiles = new Bitmap[images.length()];
            bounds = new float[tiles.length][4];
            for (int i = 0; i < tiles.length; i++) {
                JSONArray tile = images.getJSONArray(i);
                for (int j = 0; j < 4; j++) bounds[i][j] = (float)tile.getDouble(j+1) / unit;
                try (InputStream stream = assets.open("royale/" + (highlands ? "highlands_" : "") + tile.getString(0) + ".png")) {
                    BitmapFactory.Options options = new BitmapFactory.Options();
                    options.inPreferredConfig = Bitmap.Config.RGB_565;
                    tiles[i] = BitmapFactory.decodeStream(stream, null, options);
                }
            }
            names = new String[places.length()];
            landmarks = new float[names.length][2];
            labels = new RectF[names.length];
            for (int i = 0; i < names.length; i++) {
                JSONArray place = places.getJSONArray(i);
                names[i] = place.getString(0);
                landmarks[i][0] = (float)place.getDouble(1) / unit;
                landmarks[i][1] = (float)place.getDouble(2) / unit;
                labels[i] = new RectF();
            }
        } catch (IOException | org.json.JSONException e) {
            android.util.Log.w("HaloRoyale", "Could not load the island atlas", e);
            names = new String[0];
        }
    }

    private float x(float world) { return map.centerX() + (world-centerX) * scale; }
    private float y(float world) { return map.centerY() - (world-centerY) * scale; }

    private void fill(int color) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(color);
        paint.setPathEffect(null);
    }

    private void stroke(int color, float width, boolean dotted) {
        fill(color);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(width);
        if (dotted) paint.setPathEffect(dash);
    }

    private void text(Canvas c, String s, float px, float baseline, float size, int color) {
        fill(color);
        paint.setTextSize(size);
        paint.setShadowLayer(2, 0, 1, 0xff081113);
        c.drawText(s, px, baseline, paint);
        paint.clearShadowLayer();
    }

    private void places(Canvas c) {
        int used = 0;
        for (int i = 0; i < names.length; i++) {
            float px = x(landmarks[i][0]), py = y(landmarks[i][1]);
            if (!map.contains(px, py)) continue;
            fill(0xffe2d0aa);
            c.drawCircle(px, py, expanded ? 2.2f : 1.5f, paint);
            if (!expanded && scale < .25f) continue;
            float size = expanded ? 12 : 9;
            paint.setTextSize(size);
            float width = paint.measureText(names[i]);
            float lx = Math.max(map.left+2, Math.min(map.right-width-3, px+3));
            float ly = Math.min(map.bottom-3, Math.max(map.top+size+2, py-3));
            RectF label = labels[used];
            label.set(lx-2, ly-size-1, lx+width+2, ly+2);
            boolean overlaps = false;
            for (int j = 0; j < used; j++) if (RectF.intersects(label, labels[j])) overlaps = true;
            if (!overlaps) {
                text(c, names[i], lx, ly, size, 0xffedebdc);
                used++;
            }
        }
    }

    private void zones(Canvas c) {
        float cx = x(data[11]), cy = y(data[12]), r = scale*data[13];
        // The extra map-sized rectangle leaves the disk clear and shades only unsafe ground.
        path.rewind();
        path.setFillType(Path.FillType.EVEN_ODD);
        path.addRect(map, Path.Direction.CW);
        path.addCircle(cx, cy, r, Path.Direction.CW);
        fill(0x704c72bc);
        c.drawPath(path, paint);
        stroke(CURRENT, expanded ? 2.2f : 1.7f, false);
        c.drawCircle(cx, cy, r, paint);
        float tx = x(data[14]), ty = y(data[15]);
        stroke(NEXT, expanded ? 1.9f : 1.5f, true);
        c.drawCircle(tx, ty, Math.max(2, data[16]*scale), paint);
        if (!expanded && !map.contains(tx, ty)) {
            // Bearing to the next safe area at the edge of the local map.
            float dx = tx-map.centerX(), dy = ty-map.centerY();
            float k = (map.width()/2-7) / Math.max(Math.abs(dx), Math.abs(dy));
            c.save();
            c.translate(map.centerX()+dx*k, map.centerY()+dy*k);
            c.rotate((float)Math.toDegrees(Math.atan2(dy,dx)));
            path.rewind(); path.moveTo(4,0); path.lineTo(-3,-4); path.lineTo(-3,4); path.close();
            fill(NEXT); c.drawPath(path,paint); c.restore();
        }
    }

    private void player(Canvas c) {
        c.save();
        c.translate(x(data[3]), y(data[4]));
        c.rotate((float)Math.toDegrees(Math.atan2(-data[6], data[5])));
        path.rewind(); path.moveTo(7,0); path.lineTo(-5,-4.5f); path.lineTo(-2,0);
        path.lineTo(-5,4.5f); path.close();
        fill(YOU); c.drawPath(path,paint);
        stroke(0xff332b1f,1.2f,false); c.drawPath(path,paint);
        c.restore();
    }

    private void vital(Canvas canvas, String label, float y, float value, int color) {
        float left = statusLeft + 29, width = 99;
        text(canvas, label, statusLeft + 7, y + 9, 10, NEXT);
        fill(0xff42515c);
        canvas.drawRect(left, y, left + width, y + 9, paint);
        fill(color);
        canvas.drawRect(left, y, left + width * Math.max(0, Math.min(1, value)), y + 9, paint);
        if (value > 1) {
            fill(0xfff8c562);
            canvas.drawRect(left, y, left + width * Math.min(1, value - 1), y + 4, paint);
        }
    }

    void draw(Canvas c) {
        load();
        boolean flight = data[17] > 0;
        boolean outside = !flight && data[0] >= HOLD
                && Math.hypot(data[3]-data[11], data[4]-data[12]) > data[13];
        if (outside) {
            c.save();
            c.translate(viewWidth * .5f, viewHeight * .5f);
            c.scale(viewWidth * .5f, viewHeight * .5f);
            c.drawRect(-1, -1, 1, 1, stormHaze);
            c.restore();
        }
        boolean whole = expanded || flight;
        float half = whole ? Math.max(data[9]-data[7], data[10]-data[8]) * .52f :
                Math.max(65, Math.min(165, data[16] * .22f));
        if (data[0] == EXPLORE && !whole) half = 95;
        centerX = whole ? (data[7]+data[9])*.5f : data[3];
        centerY = whole ? (data[8]+data[10])*.5f : data[4];
        scale = map.width() / (2 * Math.max(.01f, half));
        fill(0xf0202c30); c.drawRoundRect(panel, 4, 4, paint);
        c.save(); c.clipRect(map);
        c.drawColor(0xff233941);
        fill(0xffffffff);
        for (int i = 0; i < tiles.length; i++) {
            float[] b = bounds[i];
            dest.set(x(b[0]), y(b[3]), x(b[2]), y(b[1]));
            if (tiles[i] != null && RectF.intersects(dest, map)) c.drawBitmap(tiles[i], null, dest, paint);
        }
        boolean hasZone = data[0] >= INSERTION;
        if (hasZone) zones(c);
        if (flight) {
            stroke(0xffe6b773, 1.6f, true);
            c.drawLine(x(data[18]), y(data[19]), x(data[20]), y(data[21]), paint);
        }
        places(c);
        player(c);
        text(c,"N",map.left+4,map.top+12,11,NEXT);
        c.restore();

        int state = (int)data[0], seconds = (int)data[2];
        String clock = String.format(Locale.ROOT,"%d:%02d",seconds/60,seconds%60);
        String title = state == EXPLORE ? "East Millworks" : state == INSERTION ? "Echo 419" :
                state == FINAL ? "Final zone closed" : (state == CLOSE ? "Closing " : "Zone " + (int)data[1] + " in ") + clock;
        double gap = hasZone ? Math.hypot(data[3]-data[14],data[4]-data[15])-data[16] : 0;
        String guide = flight ? "Flight path" : !hasZone ? "Explore" :
                outside ? "IN STORM" : gap > 0 ? "Next zone" : "Inside next zone";
        if (!flight && gap > 0) guide += "  " + Math.round(gap*unit) + " m";
        paint.setTextSize(13);
        float zoneLeft = statusLeft + 138;
        float statusRight = zoneLeft + Math.max(paint.measureText(title), paint.measureText(guide)) + 12;
        fill(0xdc17272e);
        c.drawRoundRect(statusLeft, statusTop, statusRight, statusTop + 37, 4, 4, paint);
        vital(c, "SH", statusTop + 7, data[22], CURRENT);
        vital(c, "HP", statusTop + 22, data[23], data[23] < .25f ? 0xffff795b : 0xff91df96);
        text(c,title,zoneLeft,statusTop+15,13,outside ? 0xffffa075 : NEXT);
        text(c,guide,zoneLeft,statusTop+30,12,outside ? 0xffffa075 : 0xffbadce2);
        if (expanded) {
            float left = map.left + 3, base = map.bottom+18;
            text(c,"▲ You",left,base,12,YOU);
            if (hasZone) {
                text(c,"─ Current edge",left+55,base,12,CURRENT);
                text(c,"- - Next zone",left+166,base,12,NEXT);
            }
        }
    }
}
