package io.yingshu.framekey;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;

/** Makes the receiver and controls share a row only when both have room for their contents. */
final class ConsoleLayout extends LinearLayout {
    private final LinearLayout.LayoutParams receiverParams = new LayoutParams(-1, -2);
    private final LinearLayout.LayoutParams controlsParams = new LayoutParams(-1, -2);
    private final LinearLayout receiver;
    private final View preview;
    private final View transport;
    private final LinearLayout transportPanel;
    private int viewportHeight;
    private boolean split;

    ConsoleLayout(Context context, LinearLayout receiver, View preview, View transport,
                  LinearLayout transportPanel, View controls) {
        super(context);
        this.receiver = receiver;
        this.preview = preview;
        this.transport = transport;
        this.transportPanel = transportPanel;
        setOrientation(VERTICAL);
        setGravity(Gravity.TOP);
        setBaselineAligned(false);
        controlsParams.topMargin = dp(16);
        addView(receiver, receiverParams);
        addView(controls, controlsParams);
    }

    void setViewportHeight(int height) {
        if (height != viewportHeight) {
            viewportHeight = height;
            requestLayout();
        }
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        float fontScale = getResources().getConfiguration().fontScale;
        boolean wide = MeasureSpec.getMode(widthSpec) != MeasureSpec.UNSPECIFIED &&
                MeasureSpec.getSize(widthSpec) >= dp(656 * Math.max(1f, fontScale));
        if (wide != split) {
            split = wide;
            setOrientation(wide ? HORIZONTAL : VERTICAL);
            controlsParams.width = wide ? 0 : LayoutParams.MATCH_PARENT;
            controlsParams.weight = wide ? 1f : 0f;
            boolean rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
            controlsParams.setMargins(wide && !rtl ? dp(16) : 0, wide ? 0 : dp(16),
                    wide && rtl ? dp(16) : 0, 0);
            View focus = transport.findFocus();
            LinearLayout from = wide ? receiver : transportPanel;
            LinearLayout to = wide ? transportPanel : receiver;
            from.removeView(transport);
            to.addView(transport);
            transportPanel.setVisibility(wide ? VISIBLE : GONE);
            if (focus != null && !isInTouchMode()) focus.requestFocus();
        }
        receiverParams.width = wide ? 0 : LayoutParams.MATCH_PARENT;
        receiverParams.weight = wide ? 1.6f : 0f;
        super.onMeasure(widthSpec, heightSpec);
        if (wide && viewportHeight > 0) {
            // Keep the full 16:9 frame and its result visible together in short landscape windows.
            int chromeHeight = receiver.getMeasuredHeight() - preview.getMeasuredHeight();
            int freeHeight = viewportHeight - dp(24) - chromeHeight;
            int preferredWidth = Math.max(dp(260), Math.round(freeHeight * 16f / 9f)
                    + receiver.getPaddingLeft() + receiver.getPaddingRight());
            if (receiver.getMeasuredWidth() > preferredWidth) {
                receiverParams.width = preferredWidth;
                receiverParams.weight = 0;
                super.onMeasure(widthSpec, heightSpec);
            }
        }
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
