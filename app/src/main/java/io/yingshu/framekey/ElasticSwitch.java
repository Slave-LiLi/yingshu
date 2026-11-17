package io.yingshu.framekey;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.animation.PathInterpolator;
import android.widget.CompoundButton;

/** A labeled binary switch. The drawing follows the checked state owned by CompoundButton. */
public final class ElasticSwitch extends CompoundButton {
    private static final long PRESS_MS = 110, RELEASE_MS = 200;
    private static final float HEIGHT = 30, THUMB = 22, INSET = (HEIGHT - THUMB) / 2;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path thumb = new Path(), mark = new Path();
    private final PathInterpolator easing = new PathInterpolator(.2f, 0f, 0f, 1f);
    private final ThemePalette palette;
    private final float density;
    private float trackWidth = 58, position, stretch;
    private final int touchSlop;
    private int pointerId = -1;
    private float downX, downY;
    private boolean initialized, armed;
    private ValueAnimator positionAnimator, stretchAnimator;

    public ElasticSwitch(Context context) { this(context, null); }

    public ElasticSwitch(Context context, AttributeSet attrs) { this(context, attrs, null); }

    ElasticSwitch(Context context, AttributeSet attrs, ThemePalette sharedPalette) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        palette = sharedPalette == null ? new ThemePalette() : sharedPalette;
        if (sharedPalette == null) {
            boolean night = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                    == Configuration.UI_MODE_NIGHT_YES;
            palette.setProgress(night ? 1 : 0);
        }
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        // The single chamfer repeats the keycap silhouette used in the navigation drawer.
        thumb.moveTo(5, 0);
        thumb.lineTo(16, 0);
        thumb.lineTo(THUMB, 6);
        thumb.lineTo(THUMB, 17);
        thumb.quadTo(THUMB, THUMB, 17, THUMB);
        thumb.lineTo(5, THUMB);
        thumb.quadTo(0, THUMB, 0, 17);
        thumb.lineTo(0, 5);
        thumb.quadTo(0, 0, 5, 0);
        thumb.close();
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        setButtonDrawable((Drawable) null);
        setBackground(null);
        setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        setMinHeight(Math.round(dp(48)));
        setFocusable(true);
        setClickable(true);
        position = isChecked() ? 1f : 0f;
        initialized = true;
        palette.bind(this, (control, theme) -> control.invalidate());
    }

    private float dp(float value) { return value * density; }
    private boolean rtl() { return getLayoutDirection() == View.LAYOUT_DIRECTION_RTL; }

    @Override public int getCompoundPaddingRight() {
        return super.getCompoundPaddingRight() + (initialized && !rtl() ? Math.round(dp(trackWidth + 16)) : 0);
    }

    @Override public int getCompoundPaddingLeft() {
        return super.getCompoundPaddingLeft() + (initialized && rtl() ? Math.round(dp(trackWidth + 16)) : 0);
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int available = View.MeasureSpec.getSize(widthSpec) - getPaddingLeft() - getPaddingRight();
        trackWidth = available > 0 && available < dp(260) ? 52 : 58;
        super.onMeasure(widthSpec, heightSpec);
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float left = rtl() ? getPaddingLeft() + dp(5) : getWidth() - getPaddingRight() - dp(trackWidth + 5);
        float top = (getHeight() + getPaddingTop() - getPaddingBottom() - dp(HEIGHT)) / 2;
        int save = canvas.save();
        canvas.translate(left, top);
        canvas.scale(density, density);

        int neutral = palette.color(ThemePalette.MUTED);
        int accent = palette.color(ThemePalette.ACCENT_TEXT);
        int fill = ThemePalette.blend(palette.color(ThemePalette.BG), palette.color(ThemePalette.SELECTED), position);
        int ink = ThemePalette.blend(neutral, accent, position);
        int edge = ThemePalette.blend(palette.color(ThemePalette.SWITCH_OFF), accent, position);
        if (isFocused() && !isInTouchMode()) {
            stroke(palette.color(ThemePalette.FOCUS), 2);
            canvas.drawRoundRect(-3, -3, trackWidth + 3, HEIGHT + 3, 13, 13, paint);
        }
        fill(fill);
        canvas.drawRoundRect(0, 0, trackWidth, HEIGHT, 10, 10, paint);
        stroke(edge, isPressed() ? 1.8f : 1.2f);
        canvas.drawRoundRect(.7f, .7f, trackWidth - .7f, HEIGHT - .7f, 9.3f, 9.3f, paint);

        // The exposed end of the slot shows how far the slider travels.
        float start = INSET + THUMB / 2, end = trackWidth - start, midY = HEIGHT / 2;
        stroke(ThemePalette.blend(fill, ink, .65f), 1.4f);
        canvas.drawLine(start, midY, end, midY, paint);
        canvas.drawLine(start, midY - 2, start, midY + 2, paint);
        canvas.drawLine(end, midY - 2, end, midY + 2, paint);

        float visual = rtl() ? 1 - position : position;
        float x = INSET + (trackWidth - THUMB - 2 * INSET) * visual;
        float x1 = Math.max(INSET, x + Math.min(0, stretch) * 2.6f);
        float x2 = Math.min(trackWidth - INSET, x + THUMB + Math.max(0, stretch) * 2.6f);
        canvas.translate(x1, INSET);
        canvas.scale((x2 - x1) / THUMB, 1);
        fill(ink);
        canvas.drawPath(thumb, paint);
        stroke(palette.color(ThemePalette.CARD), 1.95f);
        // Morph a dash into a tick in the slider's own coordinates, including in RTL.
        mark.rewind();
        mark.moveTo(6.2f, 11 - .4f * position);
        mark.lineTo(10.2f, 11 + 3 * position);
        mark.lineTo(15.9f, 11 - 3.5f * position);
        canvas.drawPath(mark, paint);
        canvas.restoreToCount(save);
    }

    private void fill(int color) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(color);
        if (!isEnabled()) paint.setAlpha(105);
    }

    private void stroke(int color, float width) {
        fill(color);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(width);
    }
    private boolean animateAllowed() {
        return initialized && isAttachedToWindow() && isLaidOut() && isShown() && ValueAnimator.areAnimatorsEnabled();
    }
    private void animatePosition(float target) {
        if(positionAnimator!=null){positionAnimator.cancel();positionAnimator=null;}
        if(!animateAllowed() || Math.abs(position-target)<.0001f){position=target;invalidate();return;}
        ValueAnimator animator=ValueAnimator.ofFloat(position,target);positionAnimator=animator;
        animator.setDuration(RELEASE_MS);animator.setInterpolator(easing);
        animator.addUpdateListener(value->{position=(Float)value.getAnimatedValue();invalidate();});
        animator.addListener(new AnimatorListenerAdapter(){@Override public void onAnimationEnd(Animator a){if(positionAnimator==a)positionAnimator=null;}});
        animator.start();
    }
    private void animateStretch(float target, long duration) {
        if(stretchAnimator!=null){stretchAnimator.cancel();stretchAnimator=null;}
        if(!animateAllowed() || Math.abs(stretch-target)<.0001f){stretch=target;invalidate();return;}
        ValueAnimator animator=ValueAnimator.ofFloat(stretch,target);stretchAnimator=animator;
        animator.setDuration(duration);animator.setInterpolator(easing);
        animator.addUpdateListener(value->{stretch=(Float)value.getAnimatedValue();invalidate();});
        animator.addListener(new AnimatorListenerAdapter(){@Override public void onAnimationEnd(Animator a){if(stretchAnimator==a)stretchAnimator=null;}});
        animator.start();
    }
    @Override public void setChecked(boolean checked) {
        boolean before=isChecked();
        super.setChecked(checked);
        if(initialized && before!=isChecked()) {
            animatePosition(isChecked()?1f:0f);
            animateStretch(0f,RELEASE_MS);
        }
    }
    @Override public boolean performClick() {
        return isEnabled() && super.performClick();
    }
    private boolean inside(float x,float y) {return x>=0 && x<getWidth() && y>=0 && y<getHeight();}
    private void cancelPress() {
        armed=false;pointerId=-1;setPressed(false);
        if(initialized)animateStretch(0f,RELEASE_MS);
    }
    @Override public boolean onTouchEvent(MotionEvent event) {
        if(!isEnabled())return false;
        switch(event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                pointerId=event.getPointerId(0);downX=event.getX();downY=event.getY();
                armed=inside(downX,downY);setPressed(armed);
                if(armed){
                    boolean currentRight=rtl()?!isChecked():isChecked();
                    animateStretch(currentRight?-1f:1f,PRESS_MS);
                }
                return armed;
            case MotionEvent.ACTION_MOVE:
                int index=event.findPointerIndex(pointerId);
                if(index<0){cancelPress();return true;}
                float x=event.getX(index),y=event.getY(index);
                if(armed && (!inside(x,y) || (Math.abs(y-downY)>touchSlop && Math.abs(y-downY)>Math.abs(x-downX))))cancelPress();
                return true;
            case MotionEvent.ACTION_UP:
                int up=event.findPointerIndex(pointerId);
                boolean click=armed && up>=0 && inside(event.getX(up),event.getY(up));
                cancelPress();
                if(click)performClick();
                return true;
            case MotionEvent.ACTION_POINTER_UP:
                if(event.getPointerId(event.getActionIndex())==pointerId)cancelPress();
                return true;
            case MotionEvent.ACTION_CANCEL:
                cancelPress();return true;
            default:return true;
        }
    }
    @Override public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        if(initialized && !enabled){cancelPress();snapToChecked();}
    }
    private void snapToChecked() {
        if(positionAnimator!=null){positionAnimator.cancel();positionAnimator=null;}
        if(stretchAnimator!=null){stretchAnimator.cancel();stretchAnimator=null;}
        position=isChecked()?1f:0f;stretch=0;invalidate();
    }
    @Override protected void onDetachedFromWindow() {
        armed=false;pointerId=-1;setPressed(false);snapToChecked();
        super.onDetachedFromWindow();
    }
    @Override public CharSequence getAccessibilityClassName() {return "android.widget.Switch";}
    @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setClassName(getAccessibilityClassName());info.setCheckable(true);info.setChecked(isChecked());
        if(Build.VERSION.SDK_INT>=30)info.setStateDescription(isChecked()?"已开启":"已关闭");
    }
}
