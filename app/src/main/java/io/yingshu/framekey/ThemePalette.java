package io.yingshu.framekey;

import android.graphics.Color;
import android.widget.TextView;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.Map;
import java.util.WeakHashMap;

/** Cached view/drawable bindings. Changing theme only repaints existing objects. */
final class ThemePalette {
    static final int BG=0,CARD=1,FG=2,MUTED=3,ACCENT=4,BORDER=5,WARN=6,DRAWER=7,SELECTED=8,
            PREVIEW=9,SWITCH_OFF=10,BUTTON_DISABLED=11,BUTTON_TOP=12,BUTTON_BOTTOM=13,RIPPLE=14,BUTTON_RIPPLE=15;
    private static final int[] LIGHT={0xffffffff,0xfff7f7f8,0xff202123,0xff6b6b6b,0xff3479f2,0xffe5e5e5,0xffb42318,0xfff9f9f9,0xffeaeaea,
            0xfff4f4f4,0xffd1d1d1,0xffeeeeef,0xffffffff,0xfff5f5f7,0x16000000,0x12000000};
    private static final int[] DARK={0xff212121,0xff2b2b2b,0xffececec,0xffaaaaaa,0xff3479f2,0xff424242,0xfff5a59a,0xff171717,0xff303030,
            0xff181818,0xff45454a,0xff292b30,0xff35373e,0xff2b2d33,0x20ffffff,0x20ffffff};
    interface Painter<T>{void paint(T target,ThemePalette palette);}
    static final class Ref {
        final int role,literal;
        Ref(int role,int literal){this.role=role;this.literal=literal;}
        int color(ThemePalette p){return role<0?literal:p.color(role);}
    }
    private static final class Binding<T>{
        final WeakReference<T> target;final Painter<T> painter;
        Binding(T target,Painter<T> painter){this.target=new WeakReference<>(target);this.painter=painter;}
        boolean paint(ThemePalette p){T value=target.get();if(value==null)return false;painter.paint(value,p);return true;}
    }
    private final ArrayList<Binding<?>> bindings=new ArrayList<>();
    private final WeakHashMap<TextView,Ref> texts=new WeakHashMap<>();
    private float progress;
    float progress(){return progress;}
    void setProgress(float value){progress=Math.max(0,Math.min(1,value));}
    int color(int role){return blend(LIGHT[role],DARK[role],progress);}
    static int blend(int a,int b,float f){
        return Color.argb(Math.round(Color.alpha(a)+(Color.alpha(b)-Color.alpha(a))*f),
                Math.round(Color.red(a)+(Color.red(b)-Color.red(a))*f),
                Math.round(Color.green(a)+(Color.green(b)-Color.green(a))*f),
                Math.round(Color.blue(a)+(Color.blue(b)-Color.blue(a))*f));
    }
    Ref role(int role){return new Ref(role,0);}
    Ref fixed(int color){return new Ref(-1,color);}
    Ref ref(int color){for(int role=0;role<=SELECTED;role++)if(color==color(role))return role(role);return fixed(color);}
    void text(TextView view,int color){Ref ref=ref(color);texts.put(view,ref);view.setTextColor(ref.color(this));}
    <T> void bind(T target,Painter<T> painter){bindings.add(new Binding<T>(target,painter));painter.paint(target,this);}
    void paint(){
        for(Map.Entry<TextView,Ref> entry:texts.entrySet())entry.getKey().setTextColor(entry.getValue().color(this));
        for(Iterator<Binding<?>> it=bindings.iterator();it.hasNext();)if(!it.next().paint(this))it.remove();
    }
    void clear(){bindings.clear();texts.clear();}
}
