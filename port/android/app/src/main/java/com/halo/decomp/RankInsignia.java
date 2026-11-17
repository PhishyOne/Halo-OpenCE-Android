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

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int rank;

    RankInsignia(Context context, int rankIndex) {
        super(context);
        rank = Math.max(0, Math.min(11, rankIndex));
        setContentDescription(CareerRecords.rankName(rank) +
            " UNSC Service Rank insignia");
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
        p.moveTo(26, 7);
        p.lineTo(74, 7);
        p.lineTo(91, 23);
        p.lineTo(87, 67);
        p.lineTo(50, 95);
        p.lineTo(13, 67);
        p.lineTo(9, 23);
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
        color(Color.rgb(178, 205, 220), Paint.Style.STROKE, 3);
        canvas.drawArc(24, 22, 76, 80, 110, 145, false, paint);
        canvas.drawArc(24, 22, 76, 80, 285, 145, false, paint);
        color(Color.rgb(178, 205, 220), Paint.Style.FILL, 0);
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

        int metal = rank == 11 ? Color.rgb(246, 198, 99) :
            rank >= 9 ? Color.rgb(205, 222, 233) :
            rank >= 5 ? Color.rgb(178, 208, 225) :
            Color.rgb(151, 179, 194);
        int cyan = rank == 11 ? Color.rgb(77, 219, 255) :
            Color.rgb(76, 193, 255);
        Path badge = shield();
        color(Color.rgb(13, 34, 56), Paint.Style.FILL, 0);
        canvas.drawPath(badge, paint);
        color(metal, Paint.Style.STROKE, 5);
        canvas.drawPath(badge, paint);
        // Distinct beveled inner line and electric blue edge segments.
        color(Color.rgb(70, 108, 134), Paint.Style.STROKE, 2);
        canvas.drawRoundRect(17, 17, 83, 78, 8, 8, paint);
        color(cyan, Paint.Style.STROKE, 2.5f);
        line(canvas, 20, 70, 50, 91);
        line(canvas, 50, 91, 80, 70);
        color(cyan, Paint.Style.STROKE, 5);
        switch (rank) {
            case 0: // Recruit: unmarked shield and center dot
                color(cyan, Paint.Style.FILL, 0);
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
                chevron(canvas, 31, 18);
                chevron(canvas, 45, 18);
                chevron(canvas, 59, 18);
                if (rank == 4) {
                    color(cyan, Paint.Style.FILL, 0);
                    star(canvas, 50, 78, 7.5f);
                }
                break;
            case 5:
                color(cyan, Paint.Style.FILL, 0);
                diamond(canvas, 50, 51, 15);
                break;
            case 6:
                color(cyan, Paint.Style.FILL, 0);
                diamond(canvas, 50, 37, 11);
                diamond(canvas, 50, 67, 11);
                break;
            case 7:
                color(cyan, Paint.Style.FILL, 0);
                star(canvas, 50, 51, 18);
                break;
            case 8:
                color(cyan, Paint.Style.FILL, 0);
                star(canvas, 50, 36, 12);
                star(canvas, 50, 66, 12);
                break;
            case 9:
                wreath(canvas);
                color(cyan, Paint.Style.FILL, 0);
                star(canvas, 50, 51, 14);
                break;
            case 10:
                wreath(canvas);
                color(cyan, Paint.Style.FILL, 0);
                star(canvas, 50, 38, 10);
                star(canvas, 40, 59, 9);
                star(canvas, 60, 59, 9);
                break;
            default: // Legend: luminous diamond + crown of stars
                wreath(canvas);
                color(cyan, Paint.Style.STROKE, 5);
                diamond(canvas, 50, 56, 20);
                color(metal, Paint.Style.FILL, 0);
                star(canvas, 35, 25, 6);
                star(canvas, 50, 21, 7);
                star(canvas, 65, 25, 6);
        }
        canvas.restore();
    }
}
