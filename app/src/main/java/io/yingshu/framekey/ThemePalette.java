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
            PREVIEW=9,SWITCH_OFF=10,BUTTON_DISABLED=11,BUTTON_TOP=12,BUTTON_BOTTOM=13,RIPPLE=14,BUTTON_RIPPLE=15,
            ON_ACCENT=16,ACCENT_END=17,VIOLET=18,FOCUS=19,STAGE_TOP=20,STAGE_BASE=21,STAGE_INK=22,
            CRYSTAL_MINT=23,CRYSTAL_BLUE=24,CRYSTAL_LILAC=25,ACCENT_TEXT=26;
    private static final int[] LIGHT={
            0xffedf3f7,0xfffcfdff,0xff102438,0xff425b70,0xff00686c,0xffc2d2dc,0xff9a341e,0xfff3f8fb,0xffd8eff0,
            0xff0c192c,0xff667c91,0xffe4ebf0,0xfffafdff,0xffedf4f7,0x1e00545f,0x28ffffff,
            0xffffffff,0xff155074,0xff5c439a,0xff4d697d,0xff182947,0xff0b1a30,0xffe5f6ff,
            0xff9bffec,0xff63aefa,0xffbba2ff,0xff00676b};
    private static final int[] DARK={
            0xff091221,0xff121f33,0xffedf6ff,0xffa4b6ca,0xff81ebdd,0xff34465e,0xffffae8c,0xff0e192b,0xff193844,
            0xff050d19,0xff667c91,0xff1c2b40,0xff1c3148,0xff16273c,0x308be8de,0x320a2935,
            0xff092b39,0xff80c3fc,0xffc6adff,0xff94b2c7,0xff142039,0xff071325,0xffe5f6ff,
            0xff9bffec,0xff63aefa,0xffbba2ff,0xff80eadc};

    // Mid-drag surfaces share a luminance near .179 but keep their individual hues.
    // At this level both near-black and near-white ink have at least 4.5:1 contrast.
    private static int transitionColor(int role, boolean darkInk) {
        switch (role) {
            case BG:              return 0xff657886;
            case CARD:            return 0xff6c7590;
            case DRAWER:          return 0xff6a7784;
            case SELECTED:        return 0xff5b7a88;
            case BUTTON_DISABLED: return 0xff677882;
            case BUTTON_TOP:      return 0xff63788a;
            case BUTTON_BOTTOM:   return 0xff627983;
            case ACCENT:          return 0xff258092;
            case ACCENT_END:      return 0xff4c79a5;
            case FG:              return darkInk ? 0xff010102 : 0xfffffeff;
            case MUTED:           return darkInk ? 0xff020203 : 0xfffefeff;
            case ACCENT_TEXT:     return darkInk ? 0xff000302 : 0xfffdffff;
            case WARN:            return darkInk ? 0xff040100 : 0xfffffefd;
            case VIOLET:          return darkInk ? 0xff020004 : 0xfffffefe;
            case FOCUS:           return darkInk ? 0xff010204 : 0xfffaffff;
            // The button fill travels from dark teal to light cyan, opposite to app surfaces.
            case ON_ACCENT:       return darkInk ? 0xffffffff : 0xff000000;
            default:              return 0;
        }
    }
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
    int color(int role){
        boolean firstHalf = progress < .5f;
        int middle = transitionColor(role, firstHalf);
        if (middle == 0) return blend(LIGHT[role], DARK[role], progress);
        return firstHalf ? blend(LIGHT[role], middle, progress * 2f)
                : blend(middle, DARK[role], progress * 2f - 1f);
    }
    static int blend(int a,int b,float f){
        return Color.argb(Math.round(Color.alpha(a)+(Color.alpha(b)-Color.alpha(a))*f),
                Math.round(Color.red(a)+(Color.red(b)-Color.red(a))*f),
                Math.round(Color.green(a)+(Color.green(b)-Color.green(a))*f),
                Math.round(Color.blue(a)+(Color.blue(b)-Color.blue(a))*f));
    }
    Ref role(int role){return new Ref(role,0);}
    Ref fixed(int color){return new Ref(-1,color);}
    Ref ref(int color){for(int role=0;role<LIGHT.length;role++)if(color==color(role))return role(role);return fixed(color);}
    void text(TextView view,int color){Ref ref=ref(color);texts.put(view,ref);view.setTextColor(ref.color(this));}
    <T> void bind(T target,Painter<T> painter){bindings.add(new Binding<T>(target,painter));painter.paint(target,this);}
    void paint(){
        for(Map.Entry<TextView,Ref> entry:texts.entrySet())entry.getKey().setTextColor(entry.getValue().color(this));
        for(Iterator<Binding<?>> it=bindings.iterator();it.hasNext();)if(!it.next().paint(this))it.remove();
    }
    void clear(){bindings.clear();texts.clear();}
}
