package io.yingshu.framekey;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

/** A bounded, aspect-preserving preview. Frames are never accumulated. */
public final class VideoPreview extends FrameLayout {
    private final ImageView image;
    private final TextView message;
    private boolean preview = true;
    private boolean receiving;
    private Bitmap displayed;

    private void releaseImage() {
        image.setImageDrawable(null);
        // The render thread may still hold the previous bitmap; let Android
        // release its pixels after the ImageView/render references disappear.
        displayed=null;
    }

    public VideoPreview(Context context) {
        super(context);
        boolean dark=(context.getResources().getConfiguration().uiMode&Configuration.UI_MODE_NIGHT_MASK)==Configuration.UI_MODE_NIGHT_YES;
        setBackgroundColor(dark?Color.rgb(24,24,24):Color.rgb(244,244,244));
        image=new ImageView(context);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setContentDescription("实时视频预览");
        addView(image,new FrameLayout.LayoutParams(-1,-1));
        message=new TextView(context);
        message.setTextColor(dark?Color.rgb(170,170,170):Color.rgb(107,107,107));
        message.setTextSize(13);
        message.setGravity(Gravity.CENTER);
        message.setText("选择视频源，开始接收");
        addView(message,new FrameLayout.LayoutParams(-1,-1));
    }
    @Override protected void onMeasure(int widthSpec,int heightSpec) {
        int width=MeasureSpec.getSize(widthSpec);
        int height=Math.max(1,Math.round(width*9f/16f));
        super.onMeasure(MeasureSpec.makeMeasureSpec(width,MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(height,MeasureSpec.EXACTLY));
    }
    public void setPreview(boolean enabled) {
        preview=enabled;
        if(!enabled) { releaseImage();message.setVisibility(VISIBLE);message.setText(receiving?"预览已关闭 · 继续识别":"预览已关闭"); }
        else { message.setVisibility(VISIBLE);message.setText(receiving?"等待下一帧…":"选择视频源，开始接收"); }
    }
    public void receiving(boolean value) {
        receiving=value;
        if(!value)releaseImage();
        if(!preview) { message.setVisibility(VISIBLE);message.setText(value?"预览已关闭 · 继续接收":"预览已关闭"); }
    }
    public void showFrame(Bitmap bitmap) {
        if(bitmap==null)return;
        if(!preview||!receiving){bitmap.recycle();return;}
        displayed=bitmap;
        image.setImageBitmap(bitmap);
        message.setVisibility(GONE);
    }
    public void clear(String text) { releaseImage();message.setVisibility(VISIBLE);message.setText(preview?text:(receiving?"预览已关闭 · 继续接收":"预览已关闭")); }
}
