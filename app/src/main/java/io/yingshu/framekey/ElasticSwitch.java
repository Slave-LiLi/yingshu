package io.yingshu.framekey;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
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

/** A checkable capsule with a visual-only stretch before its single click action. */
public final class ElasticSwitch extends CompoundButton {
    private static final long PRESS_MS = 140, RELEASE_MS = 240;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final PathInterpolator easing = new PathInterpolator(.2f, 0f, 0f, 1f);
    private float density, trackWidth, position, stretch;
    private int offColor, onColor, onThumbColor, focusColor, touchSlop, pointerId = -1;
    private float downX, downY;
    private boolean initialized, armed;
    private ValueAnimator positionAnimator, stretchAnimator;

    public ElasticSwitch(Context context) { this(context, null); }
    public ElasticSwitch(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        trackWidth = dp(52);
        offColor = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES ? Color.rgb(69,69,74) : Color.rgb(209,209,209);
        onColor = Color.rgb(0, 95, 112);
        onThumbColor = Color.WHITE;
        focusColor = onColor;
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        setButtonDrawable((Drawable)null);
        setBackground(null);
        setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        setMinHeight(Math.round(dp(48)));
        setFocusable(true);
        setClickable(true);
        position = isChecked() ? 1f : 0f;
        initialized = true;
    }

    private float dp(float value) { return value * density; }
    private boolean rtl() { return getLayoutDirection() == View.LAYOUT_DIRECTION_RTL; }
    public void setTrackColors(int off, int on, int onThumb, int focus) {
        offColor = off;
        onColor = on;
        onThumbColor = onThumb;
        focusColor = focus;
        invalidate();
    }
    @Override public int getCompoundPaddingRight() {
        return super.getCompoundPaddingRight() + (initialized && !rtl() ? Math.round(trackWidth + dp(12)) : 0);
    }
    @Override public int getCompoundPaddingLeft() {
        return super.getCompoundPaddingLeft() + (initialized && rtl() ? Math.round(trackWidth + dp(12)) : 0);
    }
    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int available = View.MeasureSpec.getSize(widthSpec) - getPaddingLeft() - getPaddingRight();
        trackWidth = dp(available > 0 && available < dp(260) ? 48 : 52);
        super.onMeasure(widthSpec, heightSpec);
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float h = dp(28), inset = dp(4), thumbWidth = dp(20);
        float left = rtl() ? getPaddingLeft() + dp(4) : getWidth() - getPaddingRight() - trackWidth - dp(4);
        float top = (getHeight() + getPaddingTop() - getPaddingBottom() - h) / 2f;
        float alpha = isEnabled() ? 1f : .42f;
        rect.set(left, top, left + trackWidth, top + h);
        paint.setStyle(Paint.Style.FILL);
        if (isFocused() && !isInTouchMode()) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(2));
            paint.setColor(focusColor);
            rect.inset(-dp(3), -dp(3));
            canvas.drawRoundRect(rect, h / 2 + dp(3), h / 2 + dp(3), paint);
            rect.inset(dp(3), dp(3));
            paint.setStyle(Paint.Style.FILL);
        }
        paint.setColor(blend(offColor, onColor, position));
        paint.setAlpha(Math.round(255 * alpha));
        canvas.drawRoundRect(rect, h/2f, h/2f, paint);

        float visualPosition = rtl() ? 1f-position : position;
        float x = left + inset + (trackWidth - inset*2 - thumbWidth) * visualPosition;
        float extra = thumbWidth * .22f * Math.abs(stretch);
        // Signed extension avoids a discontinuity when checked changes or a tap reverses.
        float x1 = x - (stretch < 0 ? extra : 0), x2 = x + thumbWidth + (stretch > 0 ? extra : 0);
        float y1 = top + inset, y2 = top + h - inset, radius = (h - inset*2)/2f;
        // Layered translucent capsules keep the soft shadow hardware accelerated on API26+.
        paint.setColor(Color.BLACK);
        for (int ring=3; ring>=1; ring--) {
            float spread=dp(ring*.6f), dy=dp(1.2f);
            paint.setAlpha(Math.round((ring==3?4:ring==2?7:10)*alpha));
            rect.set(x1-spread,y1-spread+dy,x2+spread,y2+spread+dy);
            canvas.drawRoundRect(rect,radius+spread,radius+spread,paint);
        }
        rect.set(x1,y1,x2,y2);
        paint.setColor(blend(Color.WHITE, onThumbColor, position));paint.setAlpha(Math.round(255*alpha));
        canvas.drawRoundRect(rect,radius,radius,paint);
        paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(.55f));
        paint.setColor(Color.rgb(160,164,170));paint.setAlpha(Math.round(90*alpha));
        canvas.drawRoundRect(rect,radius,radius,paint);
        paint.setStyle(Paint.Style.FILL);paint.setAlpha(255);
    }

    private static int blend(int a, int b, float fraction) {
        float f=Math.max(0,Math.min(1,fraction));
        return Color.rgb(Math.round(Color.red(a)+(Color.red(b)-Color.red(a))*f),
                Math.round(Color.green(a)+(Color.green(b)-Color.green(a))*f),
                Math.round(Color.blue(a)+(Color.blue(b)-Color.blue(a))*f));
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
