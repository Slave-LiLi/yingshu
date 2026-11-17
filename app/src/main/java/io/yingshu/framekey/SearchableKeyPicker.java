package io.yingshu.framekey;

import android.app.AlertDialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.TypedArray;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.CheckedTextView;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import java.util.List;

/** Temporary key selection only: the containing editor still owns Save and Cancel. */
public final class SearchableKeyPicker {
    public interface OnKeySelected { void onKeySelected(int usage); }

    public static AlertDialog show(Context context,int currentUsage,int bg,int card,int fg,
                                   int muted,int accent,int border,OnKeySelected listener){
        LinearLayout body = new LinearLayout(context) {
            @Override protected void onMeasure(int widthSpec, int heightSpec) {
                int height = dp(context, 524);
                if (MeasureSpec.getMode(heightSpec) != MeasureSpec.UNSPECIFIED) {
                    height = Math.min(height, MeasureSpec.getSize(heightSpec));
                }
                super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
            }
        };
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(context,16),dp(context,8),dp(context,16),dp(context,8));
        body.setFocusableInTouchMode(true);
        LinearLayout header = new LinearLayout(context);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title=new TextView(context);title.setText("选择按键");title.setTextColor(fg);title.setTextSize(20);
        title.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        if (android.os.Build.VERSION.SDK_INT >= 28) title.setAccessibilityHeading(true);
        header.addView(title, new LinearLayout.LayoutParams(0,-2,1));
        Button cancel = new Button(context, null, android.R.attr.borderlessButtonStyle);
        cancel.setText("取消");cancel.setTextColor(accent);cancel.setAllCaps(false);cancel.setTextSize(15);
        cancel.setMinHeight(dp(context,48));cancel.setMinWidth(dp(context,64));
        header.addView(cancel, new LinearLayout.LayoutParams(-2,-2));
        body.addView(header, new LinearLayout.LayoutParams(-1,-2));
        LinearLayout searchRow=new LinearLayout(context);searchRow.setGravity(Gravity.CENTER_VERTICAL);
        EditText search=new EditText(context);search.setSingleLine(true);search.setTextSize(15);
        search.setTextColor(fg);search.setHintTextColor(muted);search.setHint("搜索，如 F5、空格、Enter");
        search.setContentDescription("搜索按键");search.setPadding(dp(context,12),dp(context,6),dp(context,8),dp(context,6));
        search.setMinHeight(dp(context,48));
        android.graphics.drawable.StateListDrawable field = new android.graphics.drawable.StateListDrawable();
        field.addState(new int[]{android.R.attr.state_focused},surface(context,card,accent,12));
        field.addState(new int[]{},surface(context,card,border,12));
        search.setBackground(field);
        search.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        search.setImeOptions(EditorInfo.IME_ACTION_SEARCH|EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        searchRow.addView(search,new LinearLayout.LayoutParams(0,-2,1));
        Button clear=new Button(context);clear.setText("清空");clear.setAllCaps(false);clear.setTextSize(13);
        clear.setTextColor(ColorStateList.valueOf(accent));clear.setContentDescription("清空按键搜索");
        android.graphics.drawable.RippleDrawable clearSurface = new android.graphics.drawable.RippleDrawable(
                ColorStateList.valueOf((accent & 0x00ffffff) | 0x33000000),surface(context,card,border,12),null);
        clear.setBackground(clearSurface);clear.setMinWidth(0);clear.setMinimumWidth(0);clear.setMinHeight(dp(context,48));
        clear.setPadding(dp(context,12),dp(context,6),dp(context,12),dp(context,6));
        LinearLayout.LayoutParams clearParams=new LinearLayout.LayoutParams(-2,-2);clearParams.setMarginStart(dp(context,8));
        searchRow.addView(clear,clearParams);MotionFeedback.attach(clear);clear.setEnabled(false);
        body.addView(searchRow,new LinearLayout.LayoutParams(-1,-2));
        TextView count=new TextView(context);count.setTextSize(12);count.setTextColor(muted);
        count.setPadding(dp(context,2),dp(context,10),dp(context,2),dp(context,7));
        count.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);body.addView(count);
        FrameLayout results=new FrameLayout(context);
        body.addView(results,new LinearLayout.LayoutParams(-1,0,1));
        ListView list=new ListView(context);list.setChoiceMode(ListView.CHOICE_MODE_SINGLE);list.setDividerHeight(0);
        list.setCacheColorHint(bg);list.setBackgroundColor(bg);
        results.addView(list,new FrameLayout.LayoutParams(-1,-1));
        TextView empty=new TextView(context);empty.setGravity(Gravity.CENTER);empty.setTextSize(14);empty.setTextColor(muted);
        empty.setText("没有匹配的按键\n试试 F5、空格、Enter 或小键盘");empty.setPadding(dp(context,8),0,dp(context,8),0);
        results.addView(empty,new FrameLayout.LayoutParams(-1,-1));list.setEmptyView(empty);
        KeyAdapter adapter=new KeyAdapter(context,currentUsage,fg,accent);list.setAdapter(adapter);
        AlertDialog dialog=new AlertDialog.Builder(context).setView(body).create();
        cancel.setOnClickListener(v -> dialog.dismiss());
        Runnable update=()->{
            // Android may synchronously reconcile checked stable IDs inside notifyDataSetChanged.
            // Remove old positions before publishing a smaller (possibly empty) result set.
            list.clearChoices();
            adapter.entries=KeySearch.find(search.getText().toString());adapter.notifyDataSetChanged();
            int selected=-1;
            for(int i=0;i<adapter.entries.size();i++)if(adapter.entries.get(i).usage==currentUsage){selected=i;list.setItemChecked(i,true);break;}
            list.setSelection(adapter.entries.isEmpty()?AdapterView.INVALID_POSITION:search.length()==0&&selected>=0?selected:0);
            count.setText(search.length()==0?"全部 "+adapter.entries.size()+" 个按键":"找到 "+adapter.entries.size()+" 个按键");
            clear.setEnabled(search.length()>0);clear.setAlpha(search.length()>0?1f:.45f);
        };
        search.addTextChangedListener(new TextWatcher(){
            @Override public void beforeTextChanged(CharSequence s,int start,int count,int after){}
            @Override public void onTextChanged(CharSequence s,int start,int before,int count){update.run();}
            @Override public void afterTextChanged(Editable e){}
        });
        clear.setOnClickListener(v->search.setText(""));
        list.setOnItemClickListener((parent,view,position,id)->{
            // Use the entry's identity. A filtered list position must never index KeyCatalog.USAGES.
            if(position<0||position>=adapter.getCount())return;
            KeySearch.Entry selected=(KeySearch.Entry)adapter.getItem(position);
            if(id!=selected.usage)return; // Ignore a click queued for the previous filtered result set.
            dialog.dismiss();if(listener!=null)listener.onKeySelected(selected.usage);
        });
        search.setOnEditorActionListener((view,action,event)->{
            if(action!=EditorInfo.IME_ACTION_SEARCH)return false;
            hideKeyboard(context,search);return true;
        });
        ViewTreeObserver.OnGlobalLayoutListener layout = () -> {
            // A rotated phone or an open keyboard can change the dialog bounds independently.
            int width = Math.min(dp(context,480),context.getResources().getDisplayMetrics().widthPixels-dp(context,24));
            if (dialog.getWindow() != null && dialog.getWindow().getAttributes().width != width) {
                dialog.getWindow().setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT);
            }
        };
        body.getViewTreeObserver().addOnGlobalLayoutListener(layout);
        dialog.setOnDismissListener(value->{
            hideKeyboard(context,search);
            if(body.getViewTreeObserver().isAlive())body.getViewTreeObserver().removeOnGlobalLayoutListener(layout);
        });
        dialog.show();
        dialog.getWindow().setBackgroundDrawable(surface(context,bg,border,18));
        dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN|WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        layout.onGlobalLayout();MotionFeedback.attach(cancel);
        body.requestFocus();update.run();return dialog;
    }

    private static final class KeyAdapter extends BaseAdapter {
        private final Context context;
        private final int selected,fg,accent;
        private List<KeySearch.Entry> entries=KeySearch.find("");
        KeyAdapter(Context context,int selected,int fg,int accent){this.context=context;this.selected=selected;this.fg=fg;this.accent=accent;}
        @Override public int getCount(){return entries.size();}
        @Override public Object getItem(int position){return entries.get(position);}
        @Override public long getItemId(int position){return position>=0&&position<entries.size()?entries.get(position).usage:AdapterView.INVALID_ROW_ID;}
        @Override public boolean hasStableIds(){return true;}
        @Override public View getView(int position,View recycled,ViewGroup parent){
            CheckedTextView text;
            if(recycled instanceof CheckedTextView)text=(CheckedTextView)recycled;
            else{
                text=new CheckedTextView(context);text.setTextSize(15);text.setGravity(Gravity.CENTER_VERTICAL);
                text.setPadding(dp(context,10),dp(context,8),dp(context,8),dp(context,8));
                text.setMinHeight(dp(context,50));
                TypedArray attributes=context.obtainStyledAttributes(new int[]{android.R.attr.listChoiceIndicatorSingle});
                text.setCheckMarkDrawable(attributes.getDrawable(0));attributes.recycle();
                text.setCheckMarkTintList(ColorStateList.valueOf(accent));
            }
            KeySearch.Entry entry=entries.get(position);text.setText(entry.displayName());text.setTextColor(fg);text.setChecked(entry.usage==selected);
            text.setContentDescription("选择按键 "+entry.displayName()+(entry.usage==selected?"，当前选中":""));
            return text;
        }
    }
    private static void hideKeyboard(Context context,View view){
        InputMethodManager keyboard=(InputMethodManager)context.getSystemService(Context.INPUT_METHOD_SERVICE);
        if(keyboard!=null)keyboard.hideSoftInputFromWindow(view.getWindowToken(),0);
    }
    private static int dp(Context context,float value){return Math.round(value*context.getResources().getDisplayMetrics().density);}
    private static GradientDrawable surface(Context context,int color,int border,float radius){
        GradientDrawable drawable=new GradientDrawable();drawable.setColor(color);drawable.setCornerRadius(dp(context,radius));drawable.setStroke(dp(context,1),border);return drawable;
    }
    private SearchableKeyPicker(){}
}
