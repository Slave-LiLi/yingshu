package io.yingshu.framekey;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/** Container outlines for the monitor, grouped controls and the floating navigation panel. */
final class PanelFrame extends Drawable {
    enum Kind { MONITOR, SECTION, DRAWER }

    private final Kind kind;
    private final float density;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path silhouette = new Path(), rim = new Path();
    private final RectF box = new RectF();
    private int surface, edge, highlight, alpha = 255;
    private float radius;

    PanelFrame(Kind kind, float density, ThemePalette palette) {
        this.kind = kind;
        this.density = density;
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setStrokeCap(Paint.Cap.ROUND);
        palette.bind(this, PanelFrame::setPalette);
    }

    private void setPalette(ThemePalette p) {
        surface = p.color(kind == Kind.DRAWER ? ThemePalette.DRAWER : ThemePalette.CARD);
        int border = p.color(ThemePalette.BORDER);
        edge = ThemePalette.blend(surface, border, kind == Kind.SECTION ? .40f : .88f);
        highlight = kind == Kind.MONITOR
                ? p.color(ThemePalette.ACCENT_TEXT) : ThemePalette.blend(border, p.color(ThemePalette.MUTED), .18f);
        invalidateSelf();
    }

    @Override protected void onBoundsChange(Rect bounds) {
        box.set(bounds);
        box.inset(density, density);
        silhouette.rewind();
        rim.rewind();
        if (box.isEmpty()) return;
        float limit = Math.min(box.width(), box.height()) / 2;
        radius = Math.min(limit, density * (kind == Kind.DRAWER ? 22 : 14));
        float l = box.left, t = box.top, r = box.right, b = box.bottom;
        if (kind == Kind.MONITOR) {
            float cut = Math.min(limit, 20 * density);
            silhouette.moveTo(l + radius, t);
            silhouette.lineTo(r - cut, t);
            silhouette.lineTo(r, t + cut);
            silhouette.lineTo(r, b - radius);
            silhouette.quadTo(r, b, r - radius, b);
            silhouette.lineTo(l + radius, b);
            silhouette.quadTo(l, b, l, b - radius);
            silhouette.lineTo(l, t + radius);
            silhouette.quadTo(l, t, l + radius, t);
            silhouette.close();
            // Highlight the chamfer outside the video area.
            rim.moveTo(Math.max(l + radius, r - cut - 27 * density), t);
            rim.lineTo(r - cut, t);
            rim.lineTo(r, t + cut);
            rim.lineTo(r, Math.min(b - radius, t + cut + 9 * density));
        } else {
            silhouette.addRoundRect(box, radius, radius, Path.Direction.CW);
            rim.moveTo(l + radius, t);
            rim.lineTo(r - radius, t);
        }
    }

    @Override public void draw(Canvas canvas) {
        if (box.isEmpty()) return;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(surface);
        paint.setAlpha(alpha);
        canvas.drawPath(silhouette, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1, density * .8f));
        paint.setColor(edge);
        paint.setAlpha(alpha);
        canvas.drawPath(silhouette, paint);
        paint.setStrokeWidth(kind == Kind.MONITOR ? density * 2 : Math.max(1, density * .85f));
        paint.setColor(highlight);
        paint.setAlpha(alpha);
        canvas.drawPath(rim, paint);
    }

    @Override public void getOutline(Outline outline) {
        if (box.isEmpty()) { outline.setEmpty(); return; }
        if (kind == Kind.MONITOR) outline.setConvexPath(silhouette);
        else outline.setRoundRect(Math.round(box.left), Math.round(box.top), Math.round(box.right),
                Math.round(box.bottom), radius);
    }

    @Override public void setAlpha(int value) { alpha = value; invalidateSelf(); }
    @Override public int getAlpha() { return alpha; }
    @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); invalidateSelf(); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
