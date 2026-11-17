package io.yingshu.framekey;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.view.View;

/** Resolution-independent artwork for the console. Each scene is static between theme changes. */
final class SpatialArt extends View {
    enum Mode { MARK, BANNER, PREVIEW }

    private static final float SCENE_WIDTH = 320;
    private static final float[] GRID_DEPTHS = {0f, .045f, .11f, .21f, .38f, .63f, .95f};
    private static final float[] STARS = {
            21, 38, 44, 57, 86, 19, 118, 30, 75, 88, 102, 49,
            181, 20, 203, 45, 237, 24, 265, 57, 281, 31, 303, 77,
            29, 101, 245, 98, 216, 109, 144, 14, 54, 25, 260, 15
    };
    // Six vertices form a split prism. Distinct triangles keep the mark legible at app-bar size.
    private static final float A_X = 2, A_Y = -29, B_X = -17, B_Y = -1;
    private static final float C_X = -4, C_Y = 5, D_X = 19, D_Y = -2;
    private static final float E_X = 0, E_Y = 29, F_X = 7, F_Y = -8;

    private final Mode mode;
    private final Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private LinearGradient surface;
    private RadialGradient aura;
    private int mint, blue, lilac, highlight, deep, markBed, markRim;
    private final float sceneHeight;

    SpatialArt(Context context, ThemePalette palette, Mode mode) {
        super(context);
        this.mode = mode;
        sceneHeight = mode == Mode.BANNER ? 76 : 180;
        ink.setStrokeCap(Paint.Cap.ROUND);
        ink.setStrokeJoin(Paint.Join.ROUND);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        palette.bind(this, SpatialArt::applyPalette);
    }

    private void applyPalette(ThemePalette palette) {
        mint = palette.color(ThemePalette.CRYSTAL_MINT);
        blue = palette.color(ThemePalette.CRYSTAL_BLUE);
        lilac = palette.color(ThemePalette.CRYSTAL_LILAC);
        highlight = palette.color(ThemePalette.STAGE_INK);
        deep = palette.color(ThemePalette.STAGE_BASE);
        markBed = palette.color(ThemePalette.STAGE_TOP);
        markRim = ThemePalette.blend(palette.color(ThemePalette.BORDER), blue, .25f);
        surface = new LinearGradient(18, 0, 280, sceneHeight, markBed, deep, Shader.TileMode.CLAMP);
        float cx = mode == Mode.BANNER ? 245 : 159;
        float cy = mode == Mode.BANNER ? 34 : 72;
        aura = new RadialGradient(cx, cy, mode == Mode.BANNER ? 66 : 91,
                alpha(blue, 52), alpha(blue, 0), Shader.TileMode.CLAMP);
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (getWidth() == 0 || getHeight() == 0) return;
        int save = canvas.save();
        if (mode == Mode.MARK) {
            float scale = Math.min(getWidth(), getHeight()) / 48f;
            canvas.translate(getWidth() * .5f, getHeight() * .5f);
            canvas.scale(scale, scale);
            drawMark(canvas);
        } else {
            // Fill unusually wide banners without stretching the geometry.
            float scale = Math.max(getWidth() / SCENE_WIDTH, getHeight() / sceneHeight);
            float dx = (getWidth() - SCENE_WIDTH * scale) * .5f;
            canvas.translate(dx, (getHeight() - sceneHeight * scale) * .5f);
            canvas.scale(scale, scale);
            drawViewport(canvas);
        }
        canvas.restoreToCount(save);
    }

    private void drawMark(Canvas c) {
        fill(markBed);
        c.drawRoundRect(-23, -23, 23, 23, 13, 13, ink);
        stroke(markRim, 1);
        c.drawRoundRect(-22.5f, -22.5f, 22.5f, 22.5f, 12.5f, 12.5f, ink);
        c.scale(.62f, .62f);
        int save = c.save();
        c.rotate(-19);
        stroke(alpha(mint, 150), 1.6f);
        c.drawOval(-31, -12, 31, 12, ink);
        c.restoreToCount(save);
        drawPrism(c);
        save = c.save();
        c.rotate(-19);
        stroke(mint, 1.6f);
        c.drawArc(-31, -12, 31, 12, 30, 98, false, ink);
        c.restoreToCount(save);
    }

    private void drawViewport(Canvas c) {
        boolean banner = mode == Mode.BANNER;
        fill(Color.WHITE);
        ink.setShader(surface);
        c.drawRect(0, 0, SCENE_WIDTH, sceneHeight, ink);
        ink.setShader(aura);
        c.drawRect(0, 0, SCENE_WIDTH, sceneHeight, ink);
        ink.setShader(null);

        float horizon = sceneHeight * .64f;
        float centerX = banner ? 242 : 157;
        drawGround(c, centerX, horizon);
        for (int i = 0; i < STARS.length; i += 2) {
            fill(alpha((i % 6 == 0) ? lilac : mint, 82 + (i % 5) * 24));
            c.drawCircle(STARS[i], STARS[i + 1] * sceneHeight / 180f, i % 6 == 0 ? .86f : .55f, ink);
        }
        if (banner) {
            drawFormation(c, 62, 35, .84f);
            drawOrbitalPrism(c, centerX, 34, .72f);
        } else {
            drawFormation(c, 52, 78, .88f);
            drawOrbitalPrism(c, centerX, 72, 1.27f);
            drawCross(c, 263, 58, 3.5f, alpha(blue, 145));
            drawCross(c, 69, 38, 2.1f, alpha(mint, 120));
            drawCross(c, 282, 105, 2.4f, alpha(mint, 106));
        }
        drawCorners(c);
    }

    private void drawGround(Canvas c, float x, float horizon) {
        int save = c.save();
        c.clipRect(0, horizon, SCENE_WIDTH, sceneHeight);
        stroke(alpha(blue, 40), .65f);
        for (int i = -9; i <= 9; i++) {
            float bottomX = x + 44 * i;
            c.drawLine(x + i * 9, horizon, bottomX, sceneHeight, ink);
        }
        for (float depth : GRID_DEPTHS) {
            float y = horizon + depth * (sceneHeight - horizon);
            c.drawLine(0, y, SCENE_WIDTH, y, ink);
        }
        c.restoreToCount(save);
        stroke(alpha(mint, 65), .7f);
        c.drawLine(x - 49, horizon, x + 48, horizon, ink);
    }

    private void drawOrbitalPrism(Canvas c, float cx, float cy, float scale) {
        int save = c.save();
        c.translate(cx, cy);
        c.scale(scale, scale);
        orbit(c, -20, 58, 16, 0, 360, alpha(mint, 114), .8f);
        orbit(c, 31, 46, 11, 0, 360, alpha(lilac, 110), .8f);
        orbit(c, -62, 39, 10, 0, 360, alpha(blue, 80), .7f);
        drawPrism(c);
        orbit(c, -20, 58, 16, 15, 141, alpha(mint, 215), .95f);
        orbit(c, 31, 46, 11, 15, 129, alpha(lilac, 205), 1);
        drawParticle(c, -50.5f, 23.8f, mint);
        drawParticle(c, 37.4f, 23.2f, lilac);
        c.restoreToCount(save);
    }

    private void orbit(Canvas c, float angle, float width, float height,
                       float start, float sweep, int color, float lineWidth) {
        int save = c.save();
        c.rotate(angle);
        stroke(color, lineWidth);
        c.drawArc(-width, -height, width, height, start, sweep, false, ink);
        c.restoreToCount(save);
    }

    private void drawPrism(Canvas c) {
        triangle(c, A_X,A_Y, B_X,B_Y, F_X,F_Y, ThemePalette.blend(mint,highlight,.5f));
        triangle(c, A_X,A_Y, F_X,F_Y, D_X,D_Y, ThemePalette.blend(lilac,highlight,.38f));
        triangle(c, B_X,B_Y, C_X,C_Y, F_X,F_Y, ThemePalette.blend(blue,mint,.6f));
        triangle(c, C_X,C_Y, D_X,D_Y, F_X,F_Y, ThemePalette.blend(blue,lilac,.65f));
        triangle(c, B_X,B_Y+2, C_X,C_Y+2, E_X,E_Y, ThemePalette.blend(blue,deep,.45f));
        triangle(c, C_X,C_Y+2, D_X,D_Y+2, E_X,E_Y, ThemePalette.blend(blue,lilac,.4f));
        stroke(alpha(highlight, 220), .8f);
        c.drawLine(B_X,B_Y, A_X,A_Y, ink);
        c.drawLine(A_X,A_Y, F_X,F_Y, ink);
        c.drawLine(F_X,F_Y, D_X,D_Y, ink);
        stroke(alpha(mint, 200), .65f);
        c.drawLine(C_X,C_Y+2, E_X,E_Y, ink);
    }

    private void triangle(Canvas c, float x1,float y1, float x2,float y2, float x3,float y3, int color) {
        path.rewind();
        path.moveTo(x1,y1);
        path.lineTo(x2,y2);
        path.lineTo(x3,y3);
        path.close();
        fill(color);
        c.drawPath(path,ink);
    }

    private void drawFormation(Canvas c, float x, float y, float scale) {
        int save = c.save();
        c.translate(x, y);
        c.scale(scale, scale);
        stroke(alpha(mint, 77), .75f);
        c.drawLine(-17,9, 4,-6,ink);
        c.drawLine(4,-6, 28,3,ink);
        drawDrone(c, -17, 9,.77f);
        drawDrone(c, 4, -6,1.05f);
        drawDrone(c, 28, 3,.67f);
        c.restoreToCount(save);
    }

    private void drawDrone(Canvas c, float x,float y,float scale) {
        int save = c.save();
        c.translate(x, y);
        c.scale(scale, scale);
        c.rotate(-10);
        stroke(alpha(mint, 210), .85f);
        c.drawLine(-4,-2,4,2,ink);
        c.drawLine(-4,2,4,-2,ink);
        for (int lateral=-1; lateral<=1; lateral+=2) {
            for (int fore=-1; fore<=1; fore+=2) {
                float cx=4.2f*lateral, cy=2.4f*fore;
                c.drawOval(cx-1.8f,cy-.9f,cx+1.8f,cy+.9f,ink);
            }
        }
        fill(mint);
        c.drawOval(-1.3f,-1.8f,1.3f,1.8f,ink);
        c.restoreToCount(save);
    }

    private void drawParticle(Canvas c,float x,float y,int color) {
        fill(alpha(color, 36));
        c.drawCircle(x,y,4,ink);
        fill(color);
        c.drawCircle(x,y,1.75f,ink);
    }

    private void drawCross(Canvas c, float x,float y,float r,int color) {
        stroke(color,.8f);
        c.drawLine(x-r,y,x+r,y,ink);
        c.drawLine(x,y-r,x,y+r,ink);
    }

    private void drawCorners(Canvas c) {
        stroke(alpha(mint, 126),.9f);
        for (int side=0;side<2;side++) {
            float x = side==0 ? 12 : SCENE_WIDTH-12, dx = side==0 ? 6 : -6;
            c.drawLine(x,11,x+dx,11,ink);
            c.drawLine(x,11,x,17,ink);
            c.drawLine(x,sceneHeight-11,x+dx,sceneHeight-11,ink);
            c.drawLine(x,sceneHeight-11,x,sceneHeight-17,ink);
        }
    }

    private void fill(int color) {
        ink.setStyle(Paint.Style.FILL);
        ink.setColor(color);
    }

    private void stroke(int color,float width) {
        ink.setStyle(Paint.Style.STROKE);
        ink.setColor(color);
        ink.setStrokeWidth(width);
    }

    private static int alpha(int color, int opacity) {
        return (color & 0x00ffffff) | (opacity << 24);
    }
}
