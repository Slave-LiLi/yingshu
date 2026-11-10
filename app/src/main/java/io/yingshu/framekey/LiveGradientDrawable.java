package io.yingshu.framekey;

import android.graphics.drawable.GradientDrawable;

/** Per-control palette binding: parent drawables must retain this live instance. */
final class LiveGradientDrawable extends GradientDrawable {
    LiveGradientDrawable(){super();}
    LiveGradientDrawable(Orientation orientation,int[] colors){super(orientation,colors);}
    @Override public ConstantState getConstantState(){return null;}
}
