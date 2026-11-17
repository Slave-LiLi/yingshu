package io.yingshu.framekey;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

/** Folded flight mark for the navigation header, drawn in a 48-unit square. */
final class NavigationEmblem extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path upperWing = triangle(7, 24.5f, 35.5f, 9, 16.5f, 27.5f);
    private final Path keel = triangle(16.5f, 27.5f, 35.5f, 9, 12, 37.5f);
    private final Path wingShadow = triangle(12, 37.5f, 35.5f, 9, 20.5f, 28.5f);
    private final Path lowerWing = triangle(20.5f, 28.5f, 35.5f, 9, 27.5f, 35.5f);
    private final Path orbit = new Path();
    private int bed, rim, orbitInk, wingInk, keelInk, shadowInk, lowerInk;

    NavigationEmblem(Context context, ThemePalette palette) {
        super(context);
        paint.setStrokeCap(Paint.Cap.ROUND);
        orbit.moveTo(7.5f, 19.5f);
        orbit.cubicTo(10, 11.5f, 18, 7, 26.5f, 8);
        orbit.moveTo(39.5f, 24);
        orbit.cubicTo(39, 29.5f, 35.5f, 35, 31.5f, 37.5f);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        palette.bind(this, NavigationEmblem::applyPalette);
    }

    private static Path triangle(float ax, float ay, float bx, float by, float cx, float cy) {
        Path path = new Path();
        path.moveTo(ax, ay);
        path.lineTo(bx, by);
        path.lineTo(cx, cy);
        path.close();
        return path;
    }

    private void applyPalette(ThemePalette palette) {
        float dark = palette.progress();
        int blue = palette.color(ThemePalette.CRYSTAL_BLUE);
        int mint = palette.color(ThemePalette.CRYSTAL_MINT);
        int violet = palette.color(ThemePalette.CRYSTAL_LILAC);
        int base = palette.color(ThemePalette.STAGE_BASE);
        bed = ThemePalette.blend(palette.color(ThemePalette.CARD), blue, .1f);
        rim = ThemePalette.blend(palette.color(ThemePalette.BORDER), blue, .17f);
        wingInk = ThemePalette.blend(ThemePalette.blend(base, mint, .47f), mint, dark);
        lowerInk = ThemePalette.blend(ThemePalette.blend(base, violet, .57f), violet, dark);
        keelInk = ThemePalette.blend(ThemePalette.blend(base, blue, .55f), blue, dark);
        shadowInk = ThemePalette.blend(base, violet, .32f + .18f * dark);
        orbitInk = ThemePalette.blend(bed, keelInk, .58f);
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float side = Math.min(getWidth(), getHeight());
        int save = canvas.save();
        canvas.translate((getWidth() - side) * .5f, (getHeight() - side) * .5f);
        canvas.scale(side / 48f, side / 48f);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(bed);
        canvas.drawRoundRect(.6f, .6f, 47.4f, 47.4f, 13, 13, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1);
        paint.setColor(rim);
        canvas.drawRoundRect(.6f, .6f, 47.4f, 47.4f, 13, 13, paint);
        paint.setColor(orbitInk);
        canvas.drawPath(orbit, paint);
        paint.setStyle(Paint.Style.FILL);
        canvas.drawCircle(7.5f, 19.5f, 1.5f, paint);
        fill(canvas, upperWing, wingInk);
        fill(canvas, keel, keelInk);
        fill(canvas, wingShadow, shadowInk);
        fill(canvas, lowerWing, lowerInk);
        canvas.restoreToCount(save);
    }

    private void fill(Canvas canvas, Path path, int color) {
        paint.setColor(color);
        canvas.drawPath(path, paint);
    }
}
