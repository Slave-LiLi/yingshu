package io.yingshu.framekey;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;

/** Small monochrome navigation symbols drawn at a density-independent 24-unit size. */
public final class UiIcon extends Drawable {
    public static final int MENU=0,CLOSE=1,RECOGNIZE=2,KEYBOARD=3,DEVICE=4,VIDEO=5,SETTINGS=6,CHEVRON=7;
    private final int kind,size;
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    public UiIcon(int kind,int color,int size){
        this.kind=kind;this.size=size;paint.setColor(color);paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.65f);paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeJoin(Paint.Join.ROUND);
    }
    private void line(Canvas c,float x1,float y1,float x2,float y2){c.drawLine(x1,y1,x2,y2,paint);}
    public void setColor(int color){if(paint.getColor()!=color){paint.setColor(color);invalidateSelf();}}
    @Override public void draw(Canvas c){
        int saved=c.save();float side=Math.min(getBounds().width(),getBounds().height());
        c.translate(getBounds().left+(getBounds().width()-side)/2,getBounds().top+(getBounds().height()-side)/2);c.scale(side/24,side/24);
        switch(kind){
            case MENU:line(c,4,8,20,8);line(c,4,16,14,16);break;
            case CLOSE:line(c,6,6,18,18);line(c,18,6,6,18);break;
            case RECOGNIZE:
                line(c,4,9,4,4);line(c,4,4,9,4);line(c,15,4,20,4);line(c,20,4,20,9);
                line(c,4,15,4,20);line(c,4,20,9,20);line(c,15,20,20,20);line(c,20,20,20,15);
                c.drawCircle(12,12,3,paint);break;
            case KEYBOARD:
                c.drawRoundRect(2,5,22,19,3,3,paint);
                for(int y=9;y<=12;y+=3)for(int x=6;x<=18;x+=4)line(c,x,y,x+.1f,y);
                line(c,8,16,16,16);break;
            case DEVICE:
                c.drawRoundRect(6,2,18,22,3,3,paint);line(c,10,5,14,5);line(c,10,18,14,18);break;
            case VIDEO:
                c.drawRoundRect(2,5,16,19,3,3,paint);line(c,16,9,22,6);line(c,22,6,22,18);line(c,22,18,16,15);break;
            case SETTINGS:
                c.drawCircle(12,12,6,paint);c.drawCircle(12,12,2,paint);
                for(int i=0;i<8;i++){
                    double angle=i*Math.PI/4;
                    line(c,12+(float)Math.cos(angle)*6,12+(float)Math.sin(angle)*6,
                        12+(float)Math.cos(angle)*9,12+(float)Math.sin(angle)*9);
                }break;
            case CHEVRON:line(c,9,6,15,12);line(c,15,12,9,18);break;
            default:break;
        }
        c.restoreToCount(saved);
    }
    @Override public int getIntrinsicWidth(){return size;}
    @Override public int getIntrinsicHeight(){return size;}
    @Override public void setAlpha(int alpha){paint.setAlpha(alpha);invalidateSelf();}
    @Override public void setColorFilter(ColorFilter filter){paint.setColorFilter(filter);invalidateSelf();}
    @Override public int getOpacity(){return PixelFormat.TRANSLUCENT;}
}
