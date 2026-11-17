package com.halo.decomp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

/** An original small vector medal for the profile's three earned slots.
 * Tier color and geometry are based only on a real, unlocked achievement. */
final class AchievementMedal extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final String tier;

    AchievementMedal(Context context, String earnedTier) {
        super(context);
        tier = earnedTier == null ? "LOCKED" : earnedTier;
        setContentDescription("Achievement " + tier.toLowerCase() + " medal");
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
    }

    private int tierColor() {
        switch (tier) {
            case "LEGENDARY": return Color.rgb(245, 189, 89);
            case "RARE": return Color.rgb(110, 195, 255);
            case "UNCOMMON": return Color.rgb(134, 220, 162);
            case "COMMON": return Color.rgb(195, 212, 227);
            default: return Color.rgb(82, 114, 133);
        }
    }

    private void style(int color, Paint.Style mode, float thickness) {
        paint.setColor(color);
        paint.setStyle(mode);
        paint.setStrokeWidth(thickness);
        paint.setStrokeJoin(Paint.Join.ROUND);
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int width = getWidth(), height = getHeight();
        if (width <= 0 || height <= 0) return;
        canvas.save();
        float scale = Math.min(width, height) / 100f;
        canvas.translate((width - scale * 100)/2f,
                         (height - scale * 100)/2f);
        canvas.scale(scale, scale);
        int metal = tierColor();

        // Stepped halo and shield-like central medallion.
        style(Color.rgb(13, 31, 51), Paint.Style.FILL, 0);
        canvas.drawCircle(50, 50, 41, paint);
        style(metal, Paint.Style.STROKE, 5);
        canvas.drawCircle(50, 50, 39, paint);
        style(Color.rgb(62, 113, 159), Paint.Style.STROKE, 2);
        canvas.drawCircle(50, 50, 32, paint);

        if ("LOCKED".equals(tier)) {
            // Empty diamond: a locked placeholder, not a fake earned medal.
            Path diamond = new Path();
            diamond.moveTo(50, 29); diamond.lineTo(69, 50);
            diamond.lineTo(50, 71); diamond.lineTo(31, 50);
            diamond.close();
            style(metal, Paint.Style.STROKE, 5);
            canvas.drawPath(diamond, paint);
            canvas.restore();
            return;
        }

        int points = "LEGENDARY".equals(tier) ? 8 : 5;
        Path star = new Path();
        for (int i = 0; i < points * 2; i++) {
            double angle = -Math.PI / 2 + i * Math.PI / points;
            float radius = (i & 1) == 0 ? 24 : 11;
            float x = 50 + (float)Math.cos(angle) * radius;
            float y = 50 + (float)Math.sin(angle) * radius;
            if (i == 0) star.moveTo(x, y);
            else star.lineTo(x, y);
        }
        star.close();
        style(metal, Paint.Style.FILL, 0);
        canvas.drawPath(star, paint);

        if ("LEGENDARY".equals(tier)) {
            style(Color.rgb(62, 204, 255), Paint.Style.FILL, 0);
            canvas.drawCircle(50, 50, 5, paint);
        }
        canvas.restore();
    }
}
