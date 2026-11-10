package io.yingshu.framekey;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;

/** A bounded, event-driven day/night control. It never owns application theme state. */
public final class ThemeToggleView extends View {
    public interface OnThemeChangedListener { void onThemeChanged(boolean dark); }
    public interface OnThemeProgressListener { void onThemeProgress(float progress); }
    private static final long SWITCH_MS=550;
    private static final float ART_WIDTH=144f, ART_HEIGHT=76f;
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pixels=new Paint();
    private final Paint shadow=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF track=new RectF(16,10,128,66);
    private final RectF trackInset=new RectF(17,11,127,65);
    private final RectF thumb=new RectF(0,0,48,48);
    private final RectF thumbInset=new RectF(.7f,.7f,47.3f,47.3f);
    private final Path dayEdge=new Path(),nightEdge=new Path();
    private final Shader dayTrack=new LinearGradient(16,10,32,66,
            new int[]{0xffc9c7c1,0xffe8e6df,0xfff0eee8},new float[]{0,.35f,1},Shader.TileMode.CLAMP);
    private final Shader nightTrack=new LinearGradient(16,10,24,66,
            new int[]{0xff08090e,0xff171820,0xff22232b},new float[]{0,.4f,1},Shader.TileMode.CLAMP);
    private final Shader dayGlass=new LinearGradient(0,0,14,48,
            new int[]{0xfff4d376,0xffeee5ca,0xfff6f4ef},new float[]{0,.33f,1},Shader.TileMode.CLAMP);
    private final Shader nightGlass=new LinearGradient(0,0,40,48,
            new int[]{0xff4f5163,0xff292c3b,0xff222334},new float[]{0,.35f,1},Shader.TileMode.CLAMP);
    private final Shader glassBorder=new LinearGradient(0,0,48,48,
            new int[]{0xbfffffff,0x26ffffff,0x71ffffff},new float[]{0,.55f,1},Shader.TileMode.CLAMP);
    private final Shader warmHalo=new RadialGradient(25,5,39,
            new int[]{0x9affb928,0x46ffba29,0x00ffb928},new float[]{0,.3f,1},Shader.TileMode.CLAMP);
    private final Shader coolHalo=new RadialGradient(9,40,38,
            new int[]{0x9a7779ff,0x506a6fff,0x006a6fff},new float[]{0,.35f,1},Shader.TileMode.CLAMP);
    private final float[] sunX=new float[289],sunY=new float[289],moonX=new float[289],moonY=new float[289];
    private int sunCount,moonCount;
    private float shade,press,phase;
    private boolean dark,animating,armed,dragging;
    private int activePointerId=MotionEvent.INVALID_POINTER_ID;
    private final int touchSlop;
    private float downX,downY,grabOffset;
    private ValueAnimator transition,pressAnimator;
    private OnThemeChangedListener listener;
    private OnThemeProgressListener progressListener;

    public ThemeToggleView(Context context) {
        super(context);
        touchSlop=ViewConfiguration.get(context).getScaledTouchSlop();
        setClickable(true);setFocusable(true);setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        setMinimumWidth(dp(144));setMinimumHeight(dp(76));
        // Only this small control uses software shadows; there are no bitmaps or recurring timers.
        setLayerType(LAYER_TYPE_SOFTWARE,null);
        shadow.setColor(0xffc6c2b8);shadow.setShadowLayer(5,0,3,0x55000000);
        dayEdge.moveTo(1.4f,11);dayEdge.quadTo(3,1.1f,15,1.1f);dayEdge.lineTo(33,1.1f);dayEdge.quadTo(44,1.1f,46.6f,11);
        nightEdge.moveTo(1.2f,12);nightEdge.lineTo(1.2f,33);nightEdge.quadTo(1.2f,46.8f,15,46.8f);
        nightEdge.lineTo(32,46.8f);nightEdge.quadTo(43,46.8f,46.8f,36);
        for(int y=0;y<17;y++)for(int x=0;x<17;x++){
            float dx=x-8,dy=y-8;float radius=dx*dx+dy*dy;
            boolean disc=radius<=12.5f;
            boolean ray=(x==8&&Math.abs(dy)>=5&&Math.abs(dy)<=8)
                    ||(y==8&&Math.abs(dx)>=5&&Math.abs(dx)<=8)
                    ||(Math.abs(dx)==Math.abs(dy)&&Math.abs(dx)>=5&&Math.abs(dx)<=6);
            if(disc||ray){sunX[sunCount]=dx*1.4f;sunY[sunCount++]=dy*1.4f;}
            float cutX=x-10.6f,cutY=y-5.5f;
            if(radius<=38&&cutX*cutX+cutY*cutY>30){moonX[moonCount]=dx*1.4f;moonY[moonCount++]=dy*1.4f;}
        }
        updateDescription();
    }

    private int dp(float value){return Math.round(value*getResources().getDisplayMetrics().density);}
    public boolean isDark(){return dark;}
    public boolean isAnimating(){return animating||dragging;}
    public void setDark(boolean value){
        cancelTransition();releaseTouch();stopPress();dark=value;shade=value?1:0;phase=0;updateDescription();notifyProgress();invalidate();
    }
    public void setOnThemeChangedListener(OnThemeChangedListener value){listener=value;}
    public void setOnThemeProgressListener(OnThemeProgressListener value){progressListener=value;}
    public void animateDark(boolean value){releaseTouch();animatePress(0);animateTo(value,420,false);}
    private void notifyProgress(){if(progressListener!=null)progressListener.onThemeProgress(shade);}
    private void updateDescription(){setContentDescription("切换主题，当前"+(dark?"深色，点击切换到浅色":"浅色，点击切换到深色"));}

    @Override protected void onMeasure(int widthMeasureSpec,int heightMeasureSpec){
        setMeasuredDimension(resolveSize(dp(144),widthMeasureSpec),resolveSize(dp(76),heightMeasureSpec));
    }

    private static float clamp(float value){return Math.max(0,Math.min(1,value));}
    private static float smooth(float value){return value*value*(3-2*value);}
    private void fill(Canvas c,RectF rect,float radius,Shader shader,float alpha){
        paint.setStyle(Paint.Style.FILL);paint.setShader(shader);paint.setAlpha(Math.round(clamp(alpha)*255));
        c.drawRoundRect(rect,radius,radius,paint);paint.setShader(null);paint.setAlpha(255);
    }
    private void edge(Canvas c,Path path,int color,float alpha){
        if(alpha<=0)return;
        paint.setStyle(Paint.Style.STROKE);paint.setStrokeCap(Paint.Cap.ROUND);paint.setShader(null);
        paint.setColor(color);paint.setAlpha(Math.round(alpha*70));paint.setStrokeWidth(5.2f);c.drawPath(path,paint);
        paint.setAlpha(Math.round(alpha*135));paint.setStrokeWidth(2.8f);c.drawPath(path,paint);
        paint.setColor(0xffffffe9);paint.setAlpha(Math.round(alpha*245));paint.setStrokeWidth(.85f);c.drawPath(path,paint);
        paint.setStyle(Paint.Style.FILL);paint.setAlpha(255);
    }

    @Override protected void onDraw(Canvas canvas){
        super.onDraw(canvas);
        int save=canvas.save();
        float scale=Math.min(getWidth()/ART_WIDTH,getHeight()/ART_HEIGHT);
        canvas.translate((getWidth()-ART_WIDTH*scale)/2,(getHeight()-ART_HEIGHT*scale)/2);canvas.scale(scale,scale);
        if(!isEnabled())canvas.saveLayerAlpha(0,0,ART_WIDTH,ART_HEIGHT,105);
        shadow.setColor(ThemePalette.blend(0xffd0cec7,0xff0e1017,shade));canvas.drawRoundRect(track,22,22,shadow);
        fill(canvas,track,22,dayTrack,1-shade);fill(canvas,track,22,nightTrack,shade);
        paint.setStyle(Paint.Style.STROKE);paint.setColor(Color.argb(Math.round(52*(1-shade)+24*shade),255,255,255));
        paint.setStrokeWidth(.65f);canvas.drawRoundRect(trackInset,21,21,paint);paint.setStyle(Paint.Style.FILL);
        // A lower inner lip makes the unoccupied side read as a recessed slot.
        paint.setColor(Color.argb(Math.round(22+20*shade),0,0,0));paint.setStrokeWidth(1.1f);
        canvas.drawLine(38,12,106,12,paint);
        float left=76-56*shade;
        canvas.translate(left,14);
        float compression=1-.035f*press;canvas.scale(compression,compression,24,24);
        paint.setShader(warmHalo);paint.setAlpha(Math.round((1-shade)*(210+45*press)));canvas.drawCircle(25,5,39,paint);
        paint.setShader(coolHalo);paint.setAlpha(Math.round(shade*(220+35*press)));canvas.drawCircle(9,40,38,paint);
        paint.setShader(null);paint.setAlpha(255);
        shadow.setColor(0xff33333b);canvas.drawRoundRect(thumb,14,14,shadow);
        fill(canvas,thumb,14,dayGlass,1-shade);fill(canvas,thumb,14,nightGlass,shade);
        paint.setStyle(Paint.Style.STROKE);paint.setShader(glassBorder);paint.setAlpha(255);paint.setStrokeWidth(.7f);
        canvas.drawRoundRect(thumbInset,13.5f,13.5f,paint);paint.setShader(null);paint.setStyle(Paint.Style.FILL);
        edge(canvas,dayEdge,0xffffbd28,1-shade);edge(canvas,nightEdge,0xff888dff,shade);
        // Absolute progress makes particles follow a slow drag without restarting a timed effect.
        float scatter=(animating||dragging)?(float)Math.sin(Math.PI*shade):0;
        drawPixels(canvas,sunX,sunY,sunCount,1-shade,scatter,0xff4b4637,11);
        drawPixels(canvas,moonX,moonY,moonCount,shade,scatter,0xfffaf7e7,37);
        canvas.restoreToCount(save);
    }

    private void drawPixels(Canvas c,float[] xs,float[] ys,int count,float opacity,float scatter,int color,int seed){
        if(opacity<.005f)return;
        pixels.setColor(color);pixels.setAlpha(Math.round(clamp(opacity)*255));
        float half=.57f*(1-.28f*scatter);
        for(int i=0;i<count;i++){
            // Fixed particle offsets keep the dissolve stable, with no randomness or allocation while drawing.
            float jx=((i*37+seed)%23-11)*.68f,jy=((i*19+seed)%29-14)*.62f;
            float x=24+xs[i]+jx*scatter,y=24+ys[i]+jy*scatter;
            c.drawRect(x-half,y-half,x+half,y+half,pixels);
        }
    }

    private void animatePress(float target){
        if(pressAnimator!=null){pressAnimator.cancel();pressAnimator=null;}
        if(!ValueAnimator.areAnimatorsEnabled()){press=target;invalidate();return;}
        pressAnimator=ValueAnimator.ofFloat(press,target);pressAnimator.setDuration(target>0?85:160);
        pressAnimator.setInterpolator(new DecelerateInterpolator());
        pressAnimator.addUpdateListener(a->{press=(float)a.getAnimatedValue();invalidate();});pressAnimator.start();
    }

    private float artX(float x){
        float scale=Math.min(getWidth()/ART_WIDTH,getHeight()/ART_HEIGHT);
        return scale>0?(x-(getWidth()-ART_WIDTH*scale)/2)/scale:0;
    }

    private void releaseTouch(){
        activePointerId=MotionEvent.INVALID_POINTER_ID;armed=false;dragging=false;setPressed(false);
        if(getParent()!=null)getParent().requestDisallowInterceptTouchEvent(false);
    }

    private void stopPress(){
        if(pressAnimator!=null){pressAnimator.removeAllUpdateListeners();pressAnimator.cancel();pressAnimator=null;}
        press=0;
    }

    private boolean farOutsideVertically(float y){
        float margin=Math.max(touchSlop*2,dp(12));
        return y < -margin || y > getHeight()+margin;
    }

    private void cancelGesture(){
        if(activePointerId==MotionEvent.INVALID_POINTER_ID&&!armed&&!dragging)return;
        releaseTouch();animatePress(0);animateTo(dark,snapDuration(dark));
    }

    private long snapDuration(boolean target){
        return Math.round(180+140*Math.abs((target?1:0)-shade));
    }

    @Override public boolean onTouchEvent(MotionEvent event){
        if(!isEnabled())return false;
        switch(event.getActionMasked()){
            case MotionEvent.ACTION_DOWN:
                if(animating)return true;
                activePointerId=event.getPointerId(0);downX=event.getX(0);downY=event.getY(0);
                grabOffset=artX(downX)-(100-56*shade);
                armed=true;dragging=false;setPressed(true);animatePress(1);return true;
            case MotionEvent.ACTION_MOVE:
                if(activePointerId==MotionEvent.INVALID_POINTER_ID)return true;
                int index=event.findPointerIndex(activePointerId);
                if(index<0){cancelGesture();return true;}
                float x=event.getX(index),y=event.getY(index),dx=x-downX,dy=y-downY;
                if(farOutsideVertically(y)){cancelGesture();return true;}
                if(!dragging){
                    if(Math.abs(dy)>touchSlop&&Math.abs(dy)>Math.abs(dx)){
                        // Do not steal vertical scrolling from the containing ScrollView.
                        cancelGesture();return true;
                    }
                    if(Math.abs(dx)>touchSlop&&Math.abs(dx)>=Math.abs(dy)){
                        dragging=true;armed=false;
                        if(getParent()!=null)getParent().requestDisallowInterceptTouchEvent(true);
                    }
                }
                if(dragging){shade=clamp((100-(artX(x)-grabOffset))/56);notifyProgress();invalidate();}
                return true;
            case MotionEvent.ACTION_UP:
                if(activePointerId==MotionEvent.INVALID_POINTER_ID)return true;
                int upIndex=event.findPointerIndex(activePointerId);
                if(upIndex<0||farOutsideVertically(event.getY(upIndex))){cancelGesture();return true;}
                boolean wasDragging=dragging;
                boolean clicked=armed&&event.getX(upIndex)>=0&&event.getX(upIndex)<=getWidth()
                        &&event.getY(upIndex)>=0&&event.getY(upIndex)<=getHeight();
                if(wasDragging){shade=clamp((100-(artX(event.getX(upIndex))-grabOffset))/56);notifyProgress();}
                boolean target=shade>=.5f;
                releaseTouch();animatePress(0);
                if(wasDragging)animateTo(target,snapDuration(target));else if(clicked)performClick();
                return true;
            case MotionEvent.ACTION_POINTER_DOWN:
                // The first pointer remains the owner; a second finger cannot move the slider.
                return true;
            case MotionEvent.ACTION_POINTER_UP:
                if(event.getPointerId(event.getActionIndex())==activePointerId)cancelGesture();
                return true;
            case MotionEvent.ACTION_CANCEL:
                cancelGesture();return true;
            default:return true;
        }
    }

    @Override public boolean performClick(){
        if(!isEnabled()||animating||dragging||armed)return false;
        super.performClick();
        animateTo(!dark,SWITCH_MS);return true;
    }

    private void animateTo(final boolean target,long duration){
        animateTo(target,duration,true);
    }
    private void animateTo(final boolean target,long duration,final boolean notify){
        cancelTransition();final float from=shade,to=target?1:0;
        if(Math.abs(from-to)<.0001f){complete(target,notify);return;}
        if(!ValueAnimator.areAnimatorsEnabled()){complete(target,notify);return;}
        animating=true;phase=0;
        transition=ValueAnimator.ofFloat(0,1);transition.setDuration(duration);transition.setInterpolator(new LinearInterpolator());
        transition.addUpdateListener(a->{phase=(float)a.getAnimatedValue();shade=from+(to-from)*smooth(phase);notifyProgress();invalidate();});
        transition.addListener(new AnimatorListenerAdapter(){
            @Override public void onAnimationEnd(Animator animation){
                if(transition==animation){transition=null;complete(target,notify);}
            }
        });transition.start();
    }

    private void complete(boolean target,boolean notify){
        boolean changed=dark!=target;
        dark=target;shade=target?1:0;phase=0;animating=false;updateDescription();notifyProgress();invalidate();
        if(changed)sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED);
        OnThemeChangedListener current=listener;
        if(notify&&changed&&current!=null&&isAttachedToWindow()&&isEnabled())current.onThemeChanged(target);
    }

    private void cancelTransition(){
        ValueAnimator current=transition;transition=null;animating=false;
        if(current!=null){current.removeAllListeners();current.removeAllUpdateListeners();current.cancel();}
    }

    @Override public void setEnabled(boolean enabled){
        super.setEnabled(enabled);
        if(!enabled){cancelTransition();releaseTouch();stopPress();shade=dark?1:0;phase=0;notifyProgress();}
        invalidate();
    }

    @Override protected void onDetachedFromWindow(){
        cancelTransition();releaseTouch();stopPress();phase=0;shade=dark?1:0;notifyProgress();
        super.onDetachedFromWindow();
    }

    @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info){
        super.onInitializeAccessibilityNodeInfo(info);info.setClassName("android.widget.Switch");info.setCheckable(true);info.setChecked(dark);
    }
    @Override public void onInitializeAccessibilityEvent(AccessibilityEvent event){
        super.onInitializeAccessibilityEvent(event);event.setClassName("android.widget.Switch");event.setChecked(dark);
    }
}
