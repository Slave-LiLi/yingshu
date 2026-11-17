package io.yingshu.framekey;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

/** A bounded, aspect-preserving preview. Frames are never accumulated. */
public final class VideoPreview extends FrameLayout {
    private final ImageView image;
    private final TextView message;
    private final SpatialArt idleScene;
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
        this(context, initialPalette(context));
    }

    private static ThemePalette initialPalette(Context context) {
        ThemePalette palette = new ThemePalette();
        boolean dark = (context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        palette.setProgress(dark ? 1 : 0);
        return palette;
    }

    VideoPreview(Context context, ThemePalette palette) {
        super(context);
        setBackgroundColor(palette.color(ThemePalette.PREVIEW));
        idleScene = new SpatialArt(context, palette, SpatialArt.Mode.PREVIEW);
        addView(idleScene, new FrameLayout.LayoutParams(-1,-1));
        image=new ImageView(context);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setContentDescription("实时视频预览");
        addView(image,new FrameLayout.LayoutParams(-1,-1));
        message=new TextView(context);
        message.setTextSize(14);
        message.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        message.setGravity(Gravity.CENTER);
        message.setPadding(dp(12),dp(4),dp(12),dp(4));
        GradientDrawable statusBed = new GradientDrawable();
        statusBed.setCornerRadius(dp(16));
        palette.bind(statusBed, (shape, p) -> shape.setColor(p.color(ThemePalette.STAGE_BASE)));
        message.setBackground(statusBed);
        palette.bind(message, (label, p) -> label.setTextColor(p.color(ThemePalette.STAGE_INK)));
        message.setText("选择视频源，开始接收");
        FrameLayout.LayoutParams caption = new FrameLayout.LayoutParams(-2,-2,Gravity.CENTER_HORIZONTAL|Gravity.BOTTOM);
        caption.setMargins(dp(12),0,dp(12),dp(9));
        addView(message,caption);
    }

    private int dp(int value) {
        return Math.round(getResources().getDisplayMetrics().density * value);
    }

    private void showMessage(String value) {
        // A repeated "enable" may occur while the previous bitmap is still shown.
        // Only reveal the decorative stage when no bitmap occupies the viewport.
        idleScene.setVisibility(displayed == null ? VISIBLE : GONE);
        message.setText(value);
        message.setVisibility(VISIBLE);
    }
    @Override protected void onMeasure(int widthSpec,int heightSpec) {
        int width=MeasureSpec.getSize(widthSpec);
        int height=Math.max(1,Math.round(width*9f/16f));
        super.onMeasure(MeasureSpec.makeMeasureSpec(width,MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(height,MeasureSpec.EXACTLY));
    }
    public void setPreview(boolean enabled) {
        if (enabled && preview && displayed != null) return;
        preview=enabled;
        if(!enabled) { releaseImage();showMessage(receiving?"预览已关闭 · 继续识别":"预览已关闭"); }
        else { showMessage(receiving?"等待下一帧…":"选择视频源，开始接收"); }
    }
    public void receiving(boolean value) {
        receiving=value;
        if(!value)releaseImage();
        if(!preview) { showMessage(value?"预览已关闭 · 继续接收":"预览已关闭"); }
    }
    public void showFrame(Bitmap bitmap) {
        if(bitmap==null)return;
        if(!preview||!receiving){bitmap.recycle();return;}
        displayed=bitmap;
        image.setImageBitmap(bitmap);
        if (idleScene.getVisibility() != GONE) idleScene.setVisibility(GONE);
        message.setVisibility(GONE);
    }
    public void clear(String text) { releaseImage();showMessage(preview?text:(receiving?"预览已关闭 · 继续接收":"预览已关闭")); }
}
