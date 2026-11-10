package io.yingshu.framekey;

import android.animation.ValueAnimator;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.PathInterpolator;

/** Brief press feedback; normal Android click/cancel handling remains the owner of actions. */
public final class MotionFeedback {
    private MotionFeedback() { }
    public static void attach(View view) {
        view.setOnTouchListener((v,event)->{
            if(!v.isEnabled())return false;
            int action=event.getActionMasked();
            if(action==MotionEvent.ACTION_DOWN)animate(v,.982f,.958f,120);
            else if(action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_CANCEL)animate(v,1f,1f,220);
            else if(action==MotionEvent.ACTION_MOVE&&(event.getX()<0||event.getX()>v.getWidth()||event.getY()<0||event.getY()>v.getHeight()))animate(v,1f,1f,180);
            return false;
        });
        view.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener(){
            @Override public void onViewAttachedToWindow(View v) { }
            @Override public void onViewDetachedFromWindow(View v){v.animate().cancel();v.setScaleX(1);v.setScaleY(1);}
        });
    }
    private static void animate(View view,float x,float y,long duration){
        view.animate().cancel();
        if(!ValueAnimator.areAnimatorsEnabled()){view.setScaleX(1);view.setScaleY(1);return;}
        view.animate().scaleX(x).scaleY(y).setDuration(duration).setInterpolator(new PathInterpolator(.2f,0,0,1)).start();
    }
}
