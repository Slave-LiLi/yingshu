package io.yingshu.framekey;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

/** The console's navigation symbols, drawn on the same 24-unit grid in both themes. */
final class NavigationGlyph extends Drawable {
    static final int HOME = 0, KEYS = 1, DEVICES = 2, SOURCES = 3, SETTINGS = 4;
    static final int MENU = 5, CLOSE = 6, CHECK = 7;

    private final ThemePalette palette;
    private final int size;
    private final Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path outline = new Path();
    private float selection;
    private int alpha = 255;

    NavigationGlyph(ThemePalette palette, int kind, int size) {
        this.palette = palette;
        this.size = size;
        ink.setStyle(Paint.Style.STROKE);
        ink.setStrokeWidth(1.8f);
        ink.setStrokeCap(Paint.Cap.ROUND);
        ink.setStrokeJoin(Paint.Join.ROUND);
        buildOutline(kind);
        palette.bind(this, (glyph, theme) -> glyph.invalidateSelf());
    }

    void setSelection(float value) {
        float next = Math.max(0f, Math.min(1f, value));
        if (next != selection) {
            selection = next;
            invalidateSelf();
        }
    }

    private void buildOutline(int kind) {
        switch (kind) {
            case HOME:
                // An optical scan field with a faceted target at its centre.
                line(3, 8, 3, 5, 5, 3, 8, 3);
                line(16, 3, 19, 3, 21, 5, 21, 8);
                line(21, 16, 21, 19, 19, 21, 16, 21);
                line(8, 21, 5, 21, 3, 19, 3, 16);
                line(12, 7.5f, 16.5f, 12, 12, 16.5f, 7.5f, 12);
                outline.close();
                break;
            case KEYS:
                // A notched keycap carrying the familiar return arrow.
                line(4, 5, 17, 5, 21, 9, 21, 17, 19, 19, 4, 19, 3, 18, 3, 6);
                outline.close();
                line(16, 10, 16, 13, 8, 13);
                line(10.5f, 10.5f, 8, 13, 10.5f, 15.5f);
                break;
            case DEVICES:
                // Two interlocking links. The gaps leave the overlap readable at 20dp.
                outline.moveTo(9, 13);
                outline.quadTo(10, 15, 12, 15);
                outline.quadTo(13.4f, 15, 14.5f, 13.9f);
                outline.lineTo(19.5f, 8.9f);
                outline.cubicTo(23, 5.4f, 18.6f, 1, 15.1f, 4.5f);
                outline.lineTo(12.9f, 6.7f);
                outline.moveTo(15, 11);
                outline.quadTo(14, 9, 12, 9);
                outline.quadTo(10.6f, 9, 9.5f, 10.1f);
                outline.lineTo(4.5f, 15.1f);
                outline.cubicTo(1, 18.6f, 5.4f, 23, 8.9f, 19.5f);
                outline.lineTo(11.1f, 17.3f);
                break;
            case SOURCES:
                // A signal entering a monitor from the left.
                line(5, 8, 5, 5, 17, 5, 21, 9, 21, 17, 5, 17);
                line(3, 12, 13, 12);
                line(10, 9, 13, 12, 10, 15);
                line(13, 17, 13, 20);
                line(10, 20, 16, 20);
                break;
            case SETTINGS:
                line(3, 7.5f, 6, 7.5f);
                outline.addCircle(9, 7.5f, 3, Path.Direction.CW);
                line(12, 7.5f, 21, 7.5f);
                line(3, 16.5f, 12, 16.5f);
                outline.addCircle(15, 16.5f, 3, Path.Direction.CW);
                line(18, 16.5f, 21, 16.5f);
                break;
            case MENU:
                line(5, 4.5f, 17, 4.5f, 21, 8.5f, 21, 18, 19.5f, 19.5f, 4.5f, 19.5f, 3, 18, 3, 6.5f);
                outline.close();
                line(9, 5, 9, 19);
                line(13, 10, 17, 10);
                line(13, 14, 15, 14);
                break;
            case CLOSE:
                line(6, 6, 18, 18);
                line(18, 6, 6, 18);
                break;
            case CHECK:
                line(4, 12, 9.5f, 17.5f, 20, 6.5f);
                break;
            default:
                throw new IllegalArgumentException("Unknown navigation glyph " + kind);
        }
    }

    private void line(float x, float y, float... points) {
        outline.moveTo(x, y);
        for (int i = 0; i < points.length; i += 2) {
            outline.lineTo(points[i], points[i + 1]);
        }
    }

    @Override public void draw(Canvas canvas) {
        Rect bounds = getBounds();
        int side = Math.min(bounds.width(), bounds.height());
        if (side <= 0) return;
        int color = ThemePalette.blend(palette.color(ThemePalette.MUTED),
                palette.color(ThemePalette.ACCENT_TEXT), selection);
        ink.setColor(color);
        ink.setAlpha(Color.alpha(color) * alpha / 255);
        int state = canvas.save();
        canvas.translate(bounds.exactCenterX() - side / 2f, bounds.exactCenterY() - side / 2f);
        canvas.scale(side / 24f, side / 24f);
        canvas.drawPath(outline, ink);
        canvas.restoreToCount(state);
    }

    @Override public int getIntrinsicWidth() { return size; }
    @Override public int getIntrinsicHeight() { return size; }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    @Override public void setAlpha(int alpha) { this.alpha = alpha; invalidateSelf(); }
    @Override public int getAlpha() { return alpha; }
    @Override public void setColorFilter(ColorFilter filter) {
        ink.setColorFilter(filter);
        invalidateSelf();
    }
}
