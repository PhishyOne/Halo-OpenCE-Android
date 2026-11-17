package com.halo.decomp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

/** Twelve original, scalable Halo-inspired rank emblems.
 * Drawn in code; no external images, fonts, bitmap downloads or copyrighted
 * game rank artwork. Each design is distinct at phone-scale resolution. */
final class RankInsignia extends View {
    private static final String[] RANK_NAMES = {
        "Recruit", "Private", "Corporal", "Sergeant", "Staff Sergeant",
        "Lieutenant", "Captain", "Major", "Colonel", "Brigadier",
        "General", "Legend"
    };
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int rank;

    RankInsignia(Context context, int rankIndex) {
        super(context);
        rank = Math.max(0, Math.min(11, rankIndex));
        setContentDescription(RANK_NAMES[rank] + " rank insignia");
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
    }

    private void color(int value, Paint.Style style, float width) {
        paint.setColor(value);
        paint.setStyle(style);
        paint.setStrokeWidth(width);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
    }

    private Path shield() {
        Path p = new Path();
        p.moveTo(50, 5);
        p.lineTo(87, 19);
        p.lineTo(82, 65);
        p.lineTo(50, 94);
        p.lineTo(18, 65);
        p.lineTo(13, 19);
        p.close();
        return p;
    }

    private void line(Canvas canvas, float x1, float y1, float x2, float y2) {
        canvas.drawLine(x1, y1, x2, y2, paint);
    }

    private void chevron(Canvas canvas, float centerY, float span) {
        Path c = new Path();
        c.moveTo(50 - span, centerY - 7);
        c.lineTo(50, centerY + 9);
        c.lineTo(50 + span, centerY - 7);
        canvas.drawPath(c, paint);
    }

    private void diamond(Canvas canvas, float x, float y, float half) {
        Path p = new Path();
        p.moveTo(x, y - half);
        p.lineTo(x + half, y);
        p.lineTo(x, y + half);
        p.lineTo(x - half, y);
        p.close();
        canvas.drawPath(p, paint);
    }

    private void star(Canvas canvas, float x, float y, float radius) {
        Path p = new Path();
        for (int i = 0; i < 10; i++) {
            double angle = -Math.PI / 2 + i * Math.PI / 5;
            float r = i % 2 == 0 ? radius : radius * .44f;
            float px = x + (float)Math.cos(angle) * r;
            float py = y + (float)Math.sin(angle) * r;
            if (i == 0) p.moveTo(px, py);
            else p.lineTo(px, py);
        }
        p.close();
        canvas.drawPath(p, paint);
    }

    private void wreath(Canvas canvas) {
        color(Color.rgb(156, 197, 167), Paint.Style.STROKE, 3);
        canvas.drawArc(24, 22, 76, 80, 110, 145, false, paint);
        canvas.drawArc(24, 22, 76, 80, 285, 145, false, paint);
        color(Color.rgb(156, 197, 167), Paint.Style.FILL, 0);
        for (int i = 0; i < 4; i++) {
            float y = 35 + i * 10;
            diamond(canvas, 25 + i * .8f, y, 3.2f);
            diamond(canvas, 75 - i * .8f, y, 3.2f);
        }
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int width = getWidth(), height = getHeight();
        if (width <= 0 || height <= 0) return;
        canvas.save();
        float scale = Math.min(width, height) / 100f;
        canvas.translate((width - 100 * scale) / 2f,
            (height - 100 * scale) / 2f);
        canvas.scale(scale, scale);

        int metal = rank >= 11 ? Color.rgb(230, 197, 96) :
            rank >= 8 ? Color.rgb(228, 197, 122) :
            rank >= 5 ? Color.rgb(193, 216, 224) :
            rank >= 3 ? Color.rgb(206, 153, 107) :
            Color.rgb(158, 180, 189);
        Path badge = shield();
        color(Color.rgb(16, 34, 44), Paint.Style.FILL, 0);
        canvas.drawPath(badge, paint);
        color(metal, Paint.Style.STROKE, 4);
        canvas.drawPath(badge, paint);
        color(Color.rgb(71, 100, 113), Paint.Style.STROKE, 1.7f);
        canvas.drawRoundRect(24, 29, 76, 76, 7, 7, paint);

        color(metal, Paint.Style.STROKE, 6);
        switch (rank) {
            case 0: // Recruit: unmarked shield and center dot
                color(metal, Paint.Style.FILL, 0);
                canvas.drawCircle(50, 52, 6, paint);
                break;
            case 1:
                line(canvas, 33, 51, 67, 51);
                break;
            case 2:
                chevron(canvas, 45, 18);
                chevron(canvas, 62, 18);
                break;
            case 3:
            case 4:
                chevron(canvas, 34, 18);
                chevron(canvas, 48, 18);
                chevron(canvas, 62, 18);
                if (rank == 4) {
                    color(metal, Paint.Style.FILL, 0);
                    star(canvas, 50, 24, 6.5f);
                }
                break;
            case 5:
                color(metal, Paint.Style.FILL, 0);
                diamond(canvas, 50, 51, 15);
                break;
            case 6:
                color(metal, Paint.Style.FILL, 0);
                diamond(canvas, 38, 51, 10);
                diamond(canvas, 62, 51, 10);
                break;
            case 7:
                color(metal, Paint.Style.FILL, 0);
                star(canvas, 50, 51, 18);
                break;
            case 8:
                color(metal, Paint.Style.FILL, 0);
                star(canvas, 39, 51, 12);
                star(canvas, 61, 51, 12);
                break;
            case 9:
                wreath(canvas);
                color(metal, Paint.Style.FILL, 0);
                star(canvas, 50, 51, 13);
                break;
            case 10:
                wreath(canvas);
                color(metal, Paint.Style.FILL, 0);
                star(canvas, 50, 38, 10);
                star(canvas, 40, 58, 9);
                star(canvas, 60, 58, 9);
                break;
            default: // Legend: luminous diamond + crown of stars
                wreath(canvas);
                color(Color.rgb(121, 225, 211), Paint.Style.STROKE, 4);
                diamond(canvas, 50, 54, 19);
                color(metal, Paint.Style.FILL, 0);
                star(canvas, 50, 29, 10);
                star(canvas, 33, 47, 5);
                star(canvas, 67, 47, 5);
        }
        canvas.restore();
    }
}
