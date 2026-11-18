/*
 * This project-authored Yingshu integration is licensed under AGPL-3.0.
 * The limited NDI linking permission is in LICENSE-NDI-EXCEPTION.txt.
 * It does not grant exceptions for upstream or other third-party materials.
 */
package io.yingshu.framekey;

import android.Manifest;
import android.animation.ValueAnimator;
import android.view.animation.PathInterpolator;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.StateListDrawable;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.ContextThemeWrapper;
import android.view.ViewGroup;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.AdapterView;

import android.widget.CheckBox;
import android.widget.EditText;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Foreground USB/NDI video receiver with an independent M3 link. No phone-camera APIs. */
public final class MainActivity extends Activity {
    private int BG, CARD, FG, MUTED, ACCENT, BORDER, WARN, DRAWER, SELECTED;
    private boolean darkTheme;
    private final ThemePalette palette=new ThemePalette();
    private ThemeToggleView themeToggle;
    private TextView appearanceLabel;
    private final PathInterpolator motionEase=new PathInterpolator(.2f,0,0,1);
    private int appearanceMode;
    private static final String[] APPEARANCE_NAMES={"跟随系统（默认）","浅色","深色"};
    private static final int REQUEST_BLE = 101, REQUEST_CAMERA = 102;
    private static final int REQUEST_EXPORT = 103, REQUEST_ENABLE_BT = 104;
    private static final int PAGE_HOME=0, PAGE_KEYS=1, PAGE_DEVICES=2, PAGE_SOURCES=3, PAGE_SETTINGS=4;
    private static final String[] PAGE_TITLES={"识别工作台","自定义按键","设备连接","视频源","设置与诊断"};
    private final ScrollView[] pages=new ScrollView[PAGE_TITLES.length];
    private final NavigationItem[] navigationRows=new NavigationItem[PAGE_TITLES.length];
    private FrameLayout appRoot, pageHost;
    private LinearLayout appBody, drawerPanel;
    private View drawerScrim;
    private ImageButton drawerClose;
    private TextView pageTitle;
    private TextView sourceTag;
    private ImageButton menuButton;
    private boolean drawerOpen;
    private int currentPage;
    private Button sourceRunButton;
    private M3BleClient ble;
    private UsbProbe usb;
    private UvcCapture uvc;
    private NdiReceiver ndi;
    private VideoPreview videoPreview;
    private Spinner videoKind, videoSources;
    private EditText ndiHost;
    private LinearLayout ndiOptions;
    private TextView videoState, videoStats;
    private Button runVideoButton, refreshVideoButton;
    private Button changeVideoSource;
    private ElasticSwitch previewSwitch, ecoSwitch;
    private final ArrayList<UsbProbe.DeviceInfo> usbChoices = new ArrayList<>();
    private final ArrayList<NdiReceiver.Source> ndiChoices = new ArrayList<>();
    private final ArrayList<String> displayedSourceIds = new ArrayList<>();
    private int videoMode;
    private boolean captureRequested, pendingVideoUsb;
    private String lastVideo = "未启动";
    private long lastStatsLog;
    private long modelTimingStart, modelTimingCount;
    private double modelTimingPre, modelTimingRun, modelTimingPost, modelTimingAge;
    private LinearLayout bleDevices, usbDevices;
    private TextView bleState, bleDetail, usbState, usbDetail, logView, notificationView;
    private Button scanButton, disconnectButton, testButton;
    private final Map<String,String[]> discovered = new LinkedHashMap<>();
    private final ArrayList<String> logs = new ArrayList<>();
    private String phase = "IDLE", serial = "", selectedAddress = "", lastUsb = "尚未检查";
    private int pendingUsb = -1;
    private boolean destroyed,appInitialized,pendingKeysQuestion;
    private AlertDialog agreementDialog,keysQuestionDialog;
    private Button restoreRulesButton;
    private String pendingRuleExport;
    private static final int REQUEST_RULE_EXPORT=105,REQUEST_RULE_IMPORT=106;
    private static final String RULE_BACKUP="last-import-backup-v1";

    private File sessionLog;
    private ModelEngine modelEngine;
    private final RuleBook ruleBook = new RuleBook();
    private final RuleGate ruleGate = new RuleGate();
    private ElasticSwitch recognitionSwitch, outputSwitch;
    private TextView modelStatus, modelResult, outputStatus, previewRecognition;
    private LinearLayout ruleRows;
    private boolean videoLive;
    private String lastRecognition = "等待画面";
    private RuleBook.Rule recognizedRule;
    private final android.os.Handler recognitionExpiry=new android.os.Handler(android.os.Looper.getMainLooper());
    private long recognitionDisplayToken;

    @Override protected void attachBaseContext(Context base) {
        int saved=base.getSharedPreferences("appearance",Context.MODE_PRIVATE).getInt("theme",0);
        appearanceMode=saved>=0&&saved<APPEARANCE_NAMES.length?saved:0;
        // Keep the Activity attached to real system configuration. Individual native
        // dialogs/widgets use themedContext(), so switching back to system is live.
        super.attachBaseContext(base);
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);configureAppearance();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        pendingRuleExport=state==null?null:state.getString("pending-rule-export");
        if(!agreementAccepted()){showAgreement(state,true);return;}
        initializeApp(state);
    }
    private boolean agreementAccepted(){return AppAgreement.VERSION.equals(getSharedPreferences("startup-agreement",MODE_PRIVATE).getString("accepted-version",""));}
    private boolean keyQuestionAccepted(){return getSharedPreferences("custom-keys-access",MODE_PRIVATE).getBoolean("accepted",false);}
    private void initializeApp(Bundle state){
        if(appInitialized||destroyed||isFinishing())return;
        appInitialized=true;
        currentPage=state==null?PAGE_HOME:Math.max(0,Math.min(PAGE_TITLES.length-1,state.getInt("selected-page",PAGE_HOME)));
        if(currentPage==PAGE_KEYS&&!keyQuestionAccepted()){currentPage=PAGE_HOME;pendingKeysQuestion=true;}
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        sessionLog = new File(getFilesDir(), "hardware-session.log");
        try { new FileOutputStream(sessionLog, false).close(); } catch (Exception ignored) { }
        videoMode=getPreferences(MODE_PRIVATE).getInt("videoMode",0);
        loadRules();
        makeUi();
        uvc = new UvcCapture(this,new UvcCapture.Listener() {
            @Override public void onState(String p,String detail) { captureState(0,p,detail); }
            @Override public void onFrame(Bitmap frame) { receiveFrame(0,frame); }
            @Override public void onStats(String stats) { captureStats(0,stats); }
            @Override public void onLog(String line) { log("UVC "+line); }
        });
        ndi = new NdiReceiver(this,new NdiReceiver.Listener() {
            @Override public void onSources(List<NdiReceiver.Source> sources) {
                if(destroyed)return;ndiChoices.clear();ndiChoices.addAll(sources);
                if(videoMode==1&&!captureRequested)updateSourceChoices();
                log("NDI_SOURCES count="+sources.size());
            }
            @Override public void onState(String p,String detail) { captureState(1,p,detail); }
            @Override public void onFrame(NdiReceiver.Frame frame) { receiveNdiFrame(frame); }
            @Override public void onStats(String stats) { captureStats(1,stats); }
            @Override public void onLog(String line) { log("NDI "+line); }
        });
        ble = new M3BleClient(this, new M3BleClient.Listener() {
            @Override public void onState(String p, String detail) {
                if (destroyed) return;
                phase = p;
                refreshDrawerStatus();
                if (!"READY".equals(p)) ruleGate.reset();
                bleState.setText(stateName(p));
                themeText(bleState,"READY".equals(p) ? ACCENT : ("ERROR".equals(p) ? WARN : FG));
                bleDetail.setText(detail);
                scanButton.setText("SCANNING".equals(p) ? "停止扫描" : "扫描 M3");
                boolean active = "CONNECTING".equals(p) || "DISCOVERING".equals(p) || "SUBSCRIBING".equals(p) || "HANDSHAKING".equals(p) || "READY".equals(p);
                scanButton.setEnabled(!active);
                disconnectButton.setEnabled(active);
                testButton.setEnabled("READY".equals(p));
                if (!"READY".equals(p)) serial = "";
                log("BLE_STATE " + p + " | " + detail);
                drawBleDevices();
            }
            @Override public void onDevice(String address, String name, int rssi) {
                if (destroyed) return;
                boolean firstDiscovery = !discovered.containsKey(address);
                discovered.put(address, new String[]{name, String.valueOf(rssi)});
                // Keep buttons stable while advertisements arrive; refresh RSSI when scanning ends.
                if (firstDiscovery) drawBleDevices();
            }
            @Override public void onLog(String message) { log("BLE " + message); }
            @Override public void onReady(String value) {
                serial = value;
                bleDetail.setText("序列号 " + value + "\n设备已响应握手；尚未验证接收端按键。");
                log("HANDSHAKE_OK serial=" + value);
            }
            @Override public void onNotification(byte[] data) {
                notificationView.setText("最近通知  " + hex(data));
            }
        });
        usb = new UsbProbe(this, new UsbProbe.Listener() {
            @Override public void onDevices(List<UsbProbe.DeviceInfo> devices) {
                usbChoices.clear();for(UsbProbe.DeviceInfo item:devices)if(item.uvc)usbChoices.add(item);
                if(videoMode==0&&!captureRequested)updateSourceChoices();drawUsbDevices(devices);
            }
            @Override public void onLog(String message) { log("USB " + message); }
            @Override public void onResult(String status, String detail) {
                if (destroyed) return;
                lastUsb = status + " | " + detail;
                usbState.setText(usbName(status));
                themeText(usbState,"OPENED".equals(status) ? ACCENT : FG);
                usbDetail.setText(compactUsbDetail(status, detail));
                log("USB_STATE " + status + " | " + detail);
                if(pendingVideoUsb&&captureRequested&&videoMode==0) {
                    if("OPENED".equals(status)) {
                        int selected=pendingUsb;pendingVideoUsb=false;
                        uvc.setPreviewEnabled(previewSwitch.isChecked());uvc.start(selected,ecoSwitch.isChecked());
                    } else if("PERMISSION_DENIED".equals(status)||"ERROR".equals(status)||"NO_DEVICE".equals(status)) {
                        captureState(0,"ERROR",detail);
                    }
                }
            }
        });
        modelEngine = new ModelEngine(this,new ModelEngine.Listener() {
            @Override public void onReady(String detail) { if(!destroyed){modelStatus.setText("八枪模型已加载 · 38 项按键规则");log("MODEL_READY "+detail);} }
            @Override public void onResult(ModelEngine.Result result) { receiveRecognition(result); }
            @Override public void onError(String detail) { if(!destroyed){modelStatus.setText(detail);clearRecognitionDisplay("识别暂不可用");log("MODEL "+detail);} }
        });
        log("APP_START io.yingshu.framekey 0.3.14-trial / " + Build.MANUFACTURER + " " + Build.MODEL + " / Android " + Build.VERSION.RELEASE + " API " + Build.VERSION.SDK_INT);
        log("SCOPE USB/NDI + eight-gun trial; keys unassigned by default; output toggle starts off");
        usb.refresh();
        videoMode=getPreferences(MODE_PRIVATE).getInt("videoMode",0);
        videoKind.setSelection(videoMode);
        ndiOptions.setVisibility(videoMode==1?View.VISIBLE:View.GONE);
        updateSourceChoices();
        // The source page may remain hidden, so its first Spinner layout event
        // must not be the only trigger for the previous NDI mode's discovery.
        if(videoMode==1)refreshSources();
        if(pendingKeysQuestion){pendingKeysQuestion=false;appRoot.post(()->{if(!destroyed&&!isFinishing())showKeysQuestion();});}
    }

    private void configureAppearance() {
        darkTheme=appearanceMode==0?systemDark():appearanceMode==2;
        setThemeProgress(darkTheme?1:0);
    }
    private boolean systemDark(){
        return (getApplicationContext().getResources().getConfiguration().uiMode&Configuration.UI_MODE_NIGHT_MASK)==Configuration.UI_MODE_NIGHT_YES;
    }
    private void setThemeProgress(float progress){
        palette.setProgress(progress);
        BG=palette.color(ThemePalette.BG);CARD=palette.color(ThemePalette.CARD);FG=palette.color(ThemePalette.FG);
        MUTED=palette.color(ThemePalette.MUTED);ACCENT=palette.color(ThemePalette.ACCENT_TEXT);BORDER=palette.color(ThemePalette.BORDER);
        WARN=palette.color(ThemePalette.WARN);DRAWER=palette.color(ThemePalette.DRAWER);SELECTED=palette.color(ThemePalette.SELECTED);
        palette.paint();
        getWindow().setStatusBarColor(BG);getWindow().setNavigationBarColor(BG);
        boolean darkBars=progress>=.5f;
        int flags=darkBars?0:View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        if(Build.VERSION.SDK_INT>=26&&!darkBars)flags|=View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        getWindow().getDecorView().setSystemUiVisibility(flags);
    }
    private Context themedContext(){
        return themedContext(darkTheme);
    }
    private Context themedContext(boolean dark){
        ContextThemeWrapper context=new ContextThemeWrapper(this,R.style.AppTheme);
        Configuration config=new Configuration();
        config.uiMode=dark?Configuration.UI_MODE_NIGHT_YES:Configuration.UI_MODE_NIGHT_NO;
        context.applyOverrideConfiguration(config);return context;
    }
    @Override public void onConfigurationChanged(Configuration config){
        super.onConfigurationChanged(config);
        if(appearanceMode==0){
            darkTheme=(config.uiMode&Configuration.UI_MODE_NIGHT_MASK)==Configuration.UI_MODE_NIGHT_YES;
            refreshAppearanceLabel();
            if(themeToggle!=null)themeToggle.animateDark(darkTheme);else setThemeProgress(darkTheme?1:0);
        }
    }
    private void refreshAppearanceLabel(){
        if(appearanceLabel!=null)appearanceLabel.setText(appearanceMode==0?"跟随系统\n"+(darkTheme?"当前深色":"当前浅色"):APPEARANCE_NAMES[appearanceMode]);
    }

    private void makeUi() {
        appRoot=new FrameLayout(this);appRoot.setBackground(shape(BG,0,0));
        if(Build.VERSION.SDK_INT>=35)appRoot.setOnApplyWindowInsetsListener((v,insets)->{
            v.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom());return insets;
        });
        appBody=vertical();appRoot.addView(appBody,new FrameLayout.LayoutParams(-1,-1));
        LinearLayout top=horizontal();top.setPadding(dp(12),dp(6),dp(16),dp(6));
        top.setBackground(roleShape(ThemePalette.CARD,0));
        menuButton=navigationButton(NavigationGlyph.MENU,"打开导航菜单");menuButton.setOnClickListener(v->setDrawerOpen(true));
        top.addView(menuButton,new LinearLayout.LayoutParams(dp(48),dp(48)));
        pageTitle=text(PAGE_TITLES[currentPage],20,FG,true);
        LinearLayout.LayoutParams titleParams=new LinearLayout.LayoutParams(0,-2,1);titleParams.setMarginStart(dp(12));
        top.addView(pageTitle,titleParams);
        SpatialArt mark=new SpatialArt(this,palette,SpatialArt.Mode.MARK);
        LinearLayout.LayoutParams brandMarkParams=new LinearLayout.LayoutParams(dp(38),dp(38));
        brandMarkParams.setMarginEnd(dp(6));top.addView(mark,brandMarkParams);
        TextView brand=text("映枢",15,FG,true);top.addView(brand);
        appBody.addView(top,new LinearLayout.LayoutParams(-1,-2));
        sectionDivider(appBody);
        pageHost=new FrameLayout(this);appBody.addView(pageHost,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout home=createPage(PAGE_HOME),keys=createPage(PAGE_KEYS),devices=createPage(PAGE_DEVICES),
                sources=createPage(PAGE_SOURCES),settings=createPage(PAGE_SETTINGS);
        makeHomePage(home);makeKeysPage(keys);makeDevicesPage(devices);makeVideoSourcePage(sources);makeSettingsPage(settings);
        bindVideoControls();makeDrawer();setContentView(appRoot);showPage(currentPage);
    }

    private LinearLayout createPage(int index) {
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(false);pages[index]=scroll;
        LinearLayout content=vertical();
        scroll.addOnLayoutChangeListener((view,left,top,right,bottom,oldLeft,oldTop,oldRight,oldBottom)->{
            int width=right-left;
            int gutter=width>=dp(600)?dp(24):dp(16);
            int maxWidth=dp(index==PAGE_HOME?1080:720);
            int inset=Math.max(gutter,(width-maxWidth)/2);
            if(content.getPaddingLeft()!=inset)content.setPadding(inset,dp(16),inset,dp(32));
        });
        content.setPadding(dp(16),dp(16),dp(16),dp(32));
        scroll.addView(content,new ScrollView.LayoutParams(-1,-2));
        pageHost.addView(scroll,new FrameLayout.LayoutParams(-1,-1));return content;
    }

    private void makeDrawer() {
        drawerScrim = new View(this);
        palette.bind(drawerScrim, (scrim, p) -> scrim.setBackgroundColor(
                ThemePalette.blend(0x7e040c18, 0x7e0b070f, p.progress())));
        drawerScrim.setOnClickListener(v -> setDrawerOpen(false));
        drawerScrim.setFocusable(false);
        drawerScrim.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        appRoot.addView(drawerScrim, new FrameLayout.LayoutParams(-1, -1));

        drawerPanel = vertical();
        drawerPanel.setBackground(panelFrame(PanelFrame.Kind.DRAWER));
        drawerPanel.setClipToOutline(true);
        drawerPanel.setElevation(dp(12));
        drawerPanel.setClickable(true);
        drawerPanel.setFocusable(false);
        if (Build.VERSION.SDK_INT >= 28) drawerPanel.setAccessibilityPaneTitle("导航菜单");
        FrameLayout.LayoutParams panelParams = new FrameLayout.LayoutParams(
                drawerWidth(getResources().getDisplayMetrics().widthPixels), -1, Gravity.LEFT);
        panelParams.setMargins(dp(8), dp(8), 0, dp(8));
        appRoot.addView(drawerPanel, panelParams);
        appRoot.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int width = drawerWidth(r - l - v.getPaddingLeft() - v.getPaddingRight());
            if (panelParams.width != width) {
                panelParams.width = width;
                drawerPanel.setLayoutParams(panelParams);
            }
        });

        LinearLayout header = horizontal();
        header.setPadding(dp(16), dp(12), dp(10), dp(12));
        header.addView(new NavigationEmblem(this, palette), new LinearLayout.LayoutParams(dp(48), dp(48)));
        LinearLayout identity = vertical();
        identity.addView(text("映枢", 23, FG, true));
        TextView build = text("v0.3.14 · 模型 v6", 12, MUTED, false);
        build.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        identity.addView(build);
        LinearLayout.LayoutParams brand = new LinearLayout.LayoutParams(0, -2, 1);
        brand.setMarginStart(dp(12));
        header.addView(identity, brand);
        drawerClose = navigationButton(NavigationGlyph.CLOSE, "关闭导航菜单");
        drawerClose.setOnClickListener(v -> setDrawerOpen(false));
        header.addView(drawerClose, new LinearLayout.LayoutParams(dp(48), dp(48)));
        drawerPanel.addView(header);
        sectionDivider(drawerPanel);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setFocusable(false);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout list = vertical();
        list.setPadding(dp(12), 0, dp(12), dp(10));
        scroll.addView(list, new ScrollView.LayoutParams(-1, -2));
        drawerPanel.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        addNavigationHeading(list, "工作空间");
        addNavigationItem(list, PAGE_HOME);
        addNavigationItem(list, PAGE_KEYS);
        addNavigationHeading(list, "连接");
        addNavigationItem(list, PAGE_DEVICES);
        addNavigationItem(list, PAGE_SOURCES);

        // Pin settings on tall screens and keep it in the same scroll path on short screens.
        android.widget.Space remaining = new android.widget.Space(this);
        list.addView(remaining, new LinearLayout.LayoutParams(-1, 0, 1));
        View divider = new View(this);
        divider.setBackground(dividerSurface());
        LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(-1, Math.max(1, dp(.75f)));
        dividerParams.setMargins(dp(8), dp(12), dp(8), dp(8));
        list.addView(divider, dividerParams);
        addNavigationItem(list, PAGE_SETTINGS);
        setDrawerOpen(false);
    }

    private void addNavigationHeading(LinearLayout parent, String name) {
        TextView heading = text(name, 13, MUTED, true);
        heading.setPadding(dp(12), dp(14), dp(8), dp(4));
        if (Build.VERSION.SDK_INT >= 28) heading.setAccessibilityHeading(true);
        parent.addView(heading);
    }

    private void addNavigationItem(LinearLayout parent, int page) {
        NavigationItem item = new NavigationItem(this, palette, page, PAGE_TITLES[page]);
        navigationRows[page] = item;
        item.setOnClickListener(v -> showPage(page));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(4);
        parent.addView(item, params);
    }

    private int drawerWidth(int available) {
        return Math.min(dp(352), Math.max(dp(160), available - dp(50)));
    }

    private void refreshDrawerStatus() {
        if (!drawerOpen) return;
        navigationRows[PAGE_HOME].setDetail(videoLive ? "正在接收画面" : captureRequested ? "等待视频画面" : "未开始接收");
        navigationRows[PAGE_KEYS].setDetail("已分配 " + assignedRuleCount() + " / " + ruleBook.rules.length + " 项");
        navigationRows[PAGE_DEVICES].setDetail("M3 · " + stateName(phase));
        navigationRows[PAGE_SOURCES].setDetail(videoMode == 0 ? "USB 采集卡" : "NDI 网络视频");
    }

    /** Navigation changes visibility only; video and inference retain their existing owners. */
    private void showPage(int index) {
        if (index < 0 || index >= pages.length || destroyed || isFinishing()) return;
        if (index == PAGE_KEYS && !keyQuestionAccepted()) {
            setDrawerOpen(false);
            showKeysQuestion();
            return;
        }
        int previous = currentPage;
        currentPage = index;
        boolean animate = pageHost.isLaidOut() && ValueAnimator.areAnimatorsEnabled();
        for (int i = 0; i < pages.length; i++) {
            pages[i].animate().cancel();
            pages[i].setAlpha(1);
            pages[i].setTranslationY(0);
            pages[i].setVisibility(i == index ? View.VISIBLE : View.GONE);
            navigationRows[i].setCurrent(i == index, animate && drawerOpen, motionEase);
        }
        pageTitle.setText(PAGE_TITLES[index]);
        setDrawerOpen(false);
        if (previous != index && animate) {
            pages[index].setAlpha(.25f);
            pages[index].setTranslationY(dp(7));
            pages[index].animate().alpha(1f).translationY(0).setDuration(220).setInterpolator(motionEase).start();
        }
    }

    private void setDrawerOpen(boolean open) {
        boolean wasOpen = drawerOpen;
        drawerOpen = open;
        drawerPanel.animate().cancel();
        drawerScrim.animate().cancel();
        boolean animate = appRoot.isLaidOut() && ValueAnimator.areAnimatorsEnabled();
        int travel = Math.max(drawerPanel.getWidth(), drawerPanel.getLayoutParams().width) + dp(10);
        appBody.setImportantForAccessibility(open ? View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS : View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
        appBody.setDescendantFocusability(open ? ViewGroup.FOCUS_BLOCK_DESCENDANTS : ViewGroup.FOCUS_AFTER_DESCENDANTS);
        drawerPanel.setImportantForAccessibility(open ? View.IMPORTANT_FOR_ACCESSIBILITY_YES : View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        drawerPanel.setDescendantFocusability(open ? ViewGroup.FOCUS_AFTER_DESCENDANTS : ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        if (open) {
            refreshDrawerStatus();
            if (drawerPanel.getVisibility() != View.VISIBLE) {
                drawerPanel.setTranslationX(-travel);
                drawerScrim.setAlpha(0);
            }
            drawerPanel.setVisibility(View.VISIBLE);
            drawerScrim.setVisibility(View.VISIBLE);
            if (animate) {
                drawerPanel.animate().translationX(0).setDuration(260).setInterpolator(motionEase).start();
                drawerScrim.animate().alpha(1).setDuration(200).start();
            } else {
                drawerPanel.setTranslationX(0);
                drawerScrim.setAlpha(1);
            }
            if (!drawerPanel.isInTouchMode()) navigationRows[currentPage].requestFocus();
            NavigationItem active = navigationRows[currentPage];
            active.post(() -> {
                if (drawerOpen && active.isSelected()) active.requestRectangleOnScreen(
                        new android.graphics.Rect(0, 0, active.getWidth(), active.getHeight()), true);
            });
        } else {
            if (animate && wasOpen) {
                drawerPanel.animate().translationX(-travel).setDuration(190).setInterpolator(motionEase)
                        .withEndAction(() -> {
                            if (!drawerOpen) {
                                drawerPanel.setVisibility(View.GONE);
                                drawerScrim.setVisibility(View.GONE);
                            }
                        }).start();
                drawerScrim.animate().alpha(0).setDuration(160).start();
            } else {
                drawerPanel.setVisibility(View.GONE);
                drawerScrim.setVisibility(View.GONE);
            }
            if (wasOpen && !menuButton.isInTouchMode()) menuButton.requestFocus();
        }
    }

    private void makeHomePage(LinearLayout content) {
        LinearLayout monitor=card(PanelFrame.Kind.MONITOR);
        LinearLayout heading=horizontal();
        TextView caption=text("当前识别",14,FG,true);
        heading.addView(caption,new LinearLayout.LayoutParams(0,-2,1));
        sourceTag=text(videoMode==0?"USB":"NDI",12,ACCENT,true);
        sourceTag.setContentDescription(videoMode==0?"视频输入：USB":"视频输入：NDI");
        sourceTag.setTypeface(Typeface.MONOSPACE);sourceTag.setLetterSpacing(.08f);
        sourceTag.setPadding(dp(10),dp(4),dp(10),dp(4));
        sourceTag.setBackground(roleShape(ThemePalette.SELECTED,8));heading.addView(sourceTag);
        monitor.addView(heading);
        previewRecognition=text("",23,FG,true);marginAdd(monitor,previewRecognition,8,12);updatePreviewRecognition();
        FrameLayout bezel = new FrameLayout(this);
        bezel.setBackground(shape(BORDER, 3, 0));
        int inset = Math.max(1, dp(1));
        bezel.setPadding(inset, inset, inset, inset);
        videoPreview = new VideoPreview(this, palette);
        videoPreview.setBackground(roleShape(ThemePalette.PREVIEW, 0));
        bezel.addView(videoPreview, new FrameLayout.LayoutParams(-1, -2));
        monitor.addView(bezel, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout transport=vertical();
        videoState=text("未启动",13,MUTED,false);marginAdd(transport,videoState,12,12);
        runVideoButton=button("选择视频源",true);
        runVideoButton.setOnClickListener(v->{
            if(captureRequested)stopVideo("已停止");
            else if(displayedSourceIds.isEmpty())showPage(PAGE_SOURCES);
            else startVideo();
        });
        changeVideoSource=button("切换视频源",false);
        changeVideoSource.setOnClickListener(v->showPage(PAGE_SOURCES));
        changeVideoSource.setVisibility(View.GONE);
        transport.addView(pairedControls(runVideoButton,changeVideoSource));
        monitor.addView(transport);
        LinearLayout controls=vertical();
        LinearLayout transportPanel=modulePanel(UiIcon.VIDEO,"视频接收");
        transportPanel.setVisibility(View.GONE);marginAdd(controls,transportPanel,0,12);
        makeModelCard(controls);
        Button customKeys=button("自定义按键",false);customKeys.setOnClickListener(v->showPage(PAGE_KEYS));
        customKeys.setCompoundDrawablesRelativeWithIntrinsicBounds(themedIcon(UiIcon.KEYBOARD,ACCENT,20),null,themedIcon(UiIcon.CHEVRON,MUTED,18),null);
        customKeys.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);customKeys.setCompoundDrawablePadding(dp(12));
        marginAdd(controls,customKeys,0,0);
        ConsoleLayout console=new ConsoleLayout(this,monitor,videoPreview,transport,transportPanel,controls);
        content.addView(console,new LinearLayout.LayoutParams(-1,-2));
        pageHost.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->console.setViewportHeight(b-t));
    }

    private LinearLayout modulePanel(int icon, String name) {
        LinearLayout panel = card(PanelFrame.Kind.SECTION);
        LinearLayout heading = horizontal();
        ImageView mark = new ImageView(this);
        mark.setImageDrawable(themedIcon(icon, ACCENT, 18));
        mark.setScaleType(ImageView.ScaleType.CENTER);
        mark.setBackground(roleShape(ThemePalette.SELECTED, 8));
        mark.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        heading.addView(mark, new LinearLayout.LayoutParams(dp(32), dp(32)));
        TextView title = text(name, 17, FG, true);
        if (Build.VERSION.SDK_INT >= 28) title.setAccessibilityHeading(true);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, -2, 1);
        titleParams.setMarginStart(dp(12));
        heading.addView(title, titleParams);
        panel.addView(heading);
        return panel;
    }

    /** Switch to a column when the controls' actual width or system text size requires it. */
    private LinearLayout pairedControls(View first, View second) {
        LinearLayout pair = new LinearLayout(this) {
            private int measuredDirection = -1;

            @Override protected void onMeasure(int widthSpec, int heightSpec) {
                boolean stack = MeasureSpec.getSize(widthSpec) < dp(238 * getResources().getConfiguration().fontScale);
                int direction = stack ? VERTICAL : HORIZONTAL;
                if (measuredDirection != direction) {
                    measuredDirection = direction;
                    setOrientation(direction);
                    for (int i = 0; i < getChildCount(); i++) {
                        LayoutParams item = (LayoutParams) getChildAt(i).getLayoutParams();
                        item.width = stack ? -1 : 0;
                        item.height = -2;
                        item.weight = stack ? 0 : 1;
                        item.topMargin = stack && i == 1 ? dp(8) : 0;
                        item.setMarginStart(!stack && i == 1 ? dp(8) : 0);
                        item.resolveLayoutDirection(getLayoutDirection());
                    }
                }
                super.onMeasure(widthSpec, heightSpec);
            }
        };
        for (View control : new View[]{first, second}) {
            control.setMinimumHeight(dp(48));
            pair.addView(control, new LinearLayout.LayoutParams(0, -2, 1));
        }
        return pair;
    }

    private void sectionDivider(LinearLayout panel) {
        View line = new View(this);
        line.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        line.setBackground(dividerSurface());
        panel.addView(line, new LinearLayout.LayoutParams(-1, Math.max(1, dp(.75f))));
    }

    private GradientDrawable dividerSurface() {
        GradientDrawable line = new LiveGradientDrawable();
        palette.bind(line, (d, p) -> d.setColor(ThemePalette.blend(
                p.color(ThemePalette.CARD), p.color(ThemePalette.BORDER), .55f)));
        return line;
    }

    private void addSwitch(LinearLayout panel, ElasticSwitch control) {
        control.setMinHeight(dp(56));
        control.setPaddingRelative(0, dp(6), dp(2), dp(6));
        control.setTextSize(16);
        panel.addView(control, new LinearLayout.LayoutParams(-1, -2));
    }

    private ScrollView scrollableDialog(View content) {
        ScrollView scroll = new ScrollView(themedContext());
        scroll.setFillViewport(false);
        scroll.addView(content);
        return scroll;
    }

    private void makeKeysPage(LinearLayout content) {
        LinearLayout transfer = modulePanel(UiIcon.KEYBOARD, "按键配置");
        marginAdd(transfer, text("38 项规则可导出为文本，也可从文本恢复。", 14, MUTED, false), 12, 12);
        Button export = button("导出文本", false);
        export.setOnClickListener(v -> exportRulesText());
        Button incoming = button("导入文本", false);
        incoming.setOnClickListener(v -> showRulesImport(null));
        transfer.addView(pairedControls(export, incoming));
        restoreRulesButton = button("恢复导入前配置", false);
        restoreRulesButton.setOnClickListener(v -> restoreRulesBackup());
        marginAdd(transfer, restoreRulesButton, 8, 0);
        refreshRuleBackup();
        marginAdd(content, transfer, 0, 16);
        ruleRows = vertical();
        content.addView(ruleRows);
        drawRuleRows();
    }

    private void makeDevicesPage(LinearLayout content) {
        LinearLayout bluetooth = modulePanel(UiIcon.DEVICE, "M3 蓝牙");
        bleState = text("等待连接", 22, FG, true);
        marginAdd(bluetooth, bleState, 16, 4);
        bleDetail = text("扫描附近的 HCL 设备，然后选择要连接的 M3。", 14, MUTED, false);
        marginAdd(bluetooth, bleDetail, 0, 16);

        scanButton = button("扫描 M3", true);
        scanButton.setOnClickListener(v -> {
            if ("SCANNING".equals(phase)) ble.stopScan();
            else scan();
        });
        disconnectButton = button("断开", false);
        disconnectButton.setEnabled(false);
        disconnectButton.setOnClickListener(v -> ble.disconnect());
        bluetooth.addView(pairedControls(scanButton, disconnectButton));
        bleDevices = vertical();
        marginAdd(bluetooth, bleDevices, 8, 8);
        sectionDivider(bluetooth);
        notificationView = text("尚未收到设备通知", 13, MUTED, false);
        marginAdd(bluetooth, notificationView, 12, 8);
        testButton = button("手动测试一个按键…", false);
        testButton.setEnabled(false);
        testButton.setOnClickListener(v -> presentDialog(new AlertDialog.Builder(themedContext())
            .setTitle("发送一次 F13 测试键")
            .setMessage("请先在接收端打开键盘测试工具。\n\n本次会通过 M3 发送一次 F13；如果外设给此键设置了宏，也可能触发该宏。发送完成仅表示链路已提交，请以接收端结果为准。")
            .setNegativeButton("取消", null)
            .setPositiveButton("发送一次", (dialog, which) -> ble.sendTestKey(0x68)).create()));
        marginAdd(bluetooth, testButton, 0, 0);
        marginAdd(content, bluetooth, 0, 16);

        LinearLayout capture = modulePanel(UiIcon.VIDEO, "USB 采集设备");
        usbState = text("检查 USB 设备", 22, FG, true);
        marginAdd(capture, usbState, 16, 4);
        usbDetail = text("通过 OTG 连接 UVC 采集卡。", 14, MUTED, false);
        marginAdd(capture, usbDetail, 0, 12);
        Button refresh = button("刷新 USB 设备", false);
        refresh.setOnClickListener(v -> usb.refresh());
        marginAdd(capture, refresh, 0, 0);
        usbDevices = vertical();
        marginAdd(capture, usbDevices, 8, 0);
        marginAdd(capture, text("授权采集卡后，可在「视频源」选择 USB 输入。", 13, MUTED, false), 8, 0);
        marginAdd(content, capture, 0, 16);
    }

    private void makeSettingsPage(LinearLayout content) {
        LinearLayout appearance = modulePanel(UiIcon.SETTINGS, "外观");
        LinearLayout themeRow = horizontal();
        appearanceLabel = text("", 16, FG, true);
        refreshAppearanceLabel();
        themeRow.addView(appearanceLabel, new LinearLayout.LayoutParams(0, -2, 1));
        themeToggle = new ThemeToggleView(this);
        themeToggle.setDark(darkTheme);
        themeToggle.setOnThemeProgressListener(progress -> { if (!destroyed) setThemeProgress(progress); });
        themeToggle.setOnThemeChangedListener(dark -> { if (!destroyed) applyAppearance(dark ? 2 : 1); });
        themeRow.addView(themeToggle, new LinearLayout.LayoutParams(dp(128), dp(72)));
        marginAdd(appearance, themeRow, 8, 0);
        marginAdd(appearance, text("拖动或轻点切换明暗，可在「选择外观」中跟随系统。", 14, MUTED, false), 0, 12);
        Button choose = button("选择外观", false);
        choose.setOnClickListener(v -> chooseAppearance());
        marginAdd(appearance, choose, 0, 0);
        marginAdd(content, appearance, 0, 16);

        LinearLayout playback = modulePanel(UiIcon.VIDEO, "视频与性能");
        previewSwitch = themedSwitch();
        previewSwitch.setText("显示视频预览");
        previewSwitch.setChecked(getPreferences(MODE_PRIVATE).getBoolean("preview", true));
        ecoSwitch = themedSwitch();
        ecoSwitch.setText("节能模式");
        ecoSwitch.setChecked(getPreferences(MODE_PRIVATE).getBoolean("eco", false));
        addSwitch(playback, previewSwitch);
        sectionDivider(playback);
        addSwitch(playback, ecoSwitch);
        marginAdd(playback, text("关闭预览仍可识别。节能模式会降低画面采样频率。", 13, MUTED, false), 0, 12);
        sectionDivider(playback);
        videoStats = text("选择输入后点击开始接收", 13, MUTED, false);
        marginAdd(playback, videoStats, 12, 0);
        marginAdd(content, playback, 0, 16);

        LinearLayout model = modulePanel(UiIcon.RECOGNIZE, "识别模型");
        modelStatus = text("正在加载八枪模型…", 17, FG, true);
        marginAdd(model, modelStatus, 14, 4);
        modelResult = text("等待 1920 × 1080 游戏画面", 14, MUTED, false);
        marginAdd(model, modelResult, 0, 12);
        sectionDivider(model);
        marginAdd(model, text("R99 支持无镜和 1 倍。其余七把枪可配置无镜、1、2、3、4 倍，2–4 倍可调镜按 4 倍处理。", 14, MUTED, false), 12, 0);
        marginAdd(content, model, 0, 16);

        LinearLayout diagnostics = modulePanel(UiIcon.DEVICE, "诊断记录");
        Button export = button("导出记录", false);
        export.setOnClickListener(v -> exportReport());
        Button copy = button("复制摘要", false);
        copy.setOnClickListener(v -> {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            clipboard.setPrimaryClip(ClipData.newPlainText("映枢连接摘要", summary()));
            Toast.makeText(this, "已复制连接摘要", Toast.LENGTH_SHORT).show();
        });
        marginAdd(diagnostics, pairedControls(export, copy), 16, 0);
        logView = text("准备就绪", 13, MUTED, false);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextIsSelectable(true);
        marginAdd(diagnostics, logView, 12, 0);
        marginAdd(content, diagnostics, 0, 16);
        Button agreement = button("查看开源许可", false);
        agreement.setOnClickListener(v -> showAgreement(null, false));
        marginAdd(content, agreement, 0, 0);
        marginAdd(content, text("离开应用时视频接收会停止，M3 会断开连接。", 13, MUTED, false), 12, 0);
    }

    private void chooseAppearance() {
        final int[] choice={appearanceMode};
        AlertDialog.Builder dialog=new AlertDialog.Builder(themedContext()).setTitle("选择外观")
                .setSingleChoiceItems(APPEARANCE_NAMES,appearanceMode,(d,which)->choice[0]=which)
                .setNegativeButton("取消",null)
                .setPositiveButton("保存",(d,which)->{
                    applyAppearance(choice[0]);
                });
        presentDialog(dialog.create());
    }

    private void applyAppearance(int mode){
        if(mode==appearanceMode)return;
        if(!getSharedPreferences("appearance",MODE_PRIVATE).edit().putInt("theme",mode).commit()){
            if(themeToggle!=null)themeToggle.setDark(darkTheme);
            Toast.makeText(this,"保存失败，原外观保留",Toast.LENGTH_SHORT).show();return;
        }
        appearanceMode=mode;darkTheme=mode==0?systemDark():mode==2;refreshAppearanceLabel();
        // Retain the same hierarchy, model and hardware owners; only their UI palette changes.
        themeToggle.animateDark(darkTheme);
    }
    private void showAgreement(Bundle saved,boolean required){
        if(destroyed||isFinishing()||(agreementDialog!=null&&agreementDialog.isShowing()))return;
        final String agreement;
        try{agreement=AppAgreement.read(this);}catch(Exception error){
            Toast.makeText(this,"无法读取许可说明，请重新安装完整安装包",Toast.LENGTH_LONG).show();if(required)finishAndRemoveTask();return;
        }
        if(required){
            FrameLayout welcome=new FrameLayout(this);welcome.setBackground(roleShape(ThemePalette.BG,0));
            TextView heading=text("映枢 · AGPL 开源",20,FG,true);heading.setGravity(Gravity.CENTER);
            welcome.addView(heading,new FrameLayout.LayoutParams(-1,-1));setContentView(welcome);
        }
        TextView body=text(agreement,15,FG,false);body.setTextIsSelectable(true);body.setPadding(dp(22),dp(12),dp(22),dp(12));
        ScrollView scroll=new ScrollView(themedContext());scroll.addView(body);
        LinearLayout holder=vertical();holder.addView(scroll,new LinearLayout.LayoutParams(-1,Math.min(dp(420),getResources().getDisplayMetrics().heightPixels/2)));
        AlertDialog.Builder builder=new AlertDialog.Builder(themedContext()).setTitle(AppAgreement.TITLE).setView(holder);
        if(required)builder.setPositiveButton("已阅读并继续",null).setNegativeButton("退出应用",(dialog,which)->finishAndRemoveTask()).setCancelable(false);
        else builder.setPositiveButton("关闭",null);
        AlertDialog dialog=builder.create();agreementDialog=dialog;dialog.setCanceledOnTouchOutside(!required);
        dialog.setOnDismissListener(d->{if(agreementDialog==dialog)agreementDialog=null;});presentDialog(dialog);
        if(required)dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            android.content.SharedPreferences prefs=getSharedPreferences("startup-agreement",MODE_PRIVATE);String prior=prefs.getString("accepted-version",null);
            if(!prefs.edit().putString("accepted-version",AppAgreement.VERSION).commit()){
                android.content.SharedPreferences.Editor rollback=prefs.edit();if(prior==null)rollback.remove("accepted-version");else rollback.putString("accepted-version",prior);rollback.commit();
                Toast.makeText(this,"无法保存确认，请重试",Toast.LENGTH_SHORT).show();return;
            }
            dialog.dismiss();initializeApp(saved);
        });
    }
    private void showKeysQuestion(){
        if(!appInitialized||destroyed||isFinishing()||(keysQuestionDialog!=null&&keysQuestionDialog.isShowing()))return;
        AlertDialog dialog=new AlertDialog.Builder(themedContext())
            .setMessage("HCL是不是专制主义？").setPositiveButton("是",null)
            .setNegativeButton("不是",(d,which)->finishAndRemoveTask()).setCancelable(false).create();
        keysQuestionDialog=dialog;dialog.setCanceledOnTouchOutside(false);
        dialog.setOnDismissListener(d->{if(keysQuestionDialog==dialog)keysQuestionDialog=null;});presentDialog(dialog);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            android.content.SharedPreferences prefs=getSharedPreferences("custom-keys-access",MODE_PRIVATE);boolean had=prefs.contains("accepted"),prior=prefs.getBoolean("accepted",false);
            if(!prefs.edit().putBoolean("accepted",true).commit()){
                android.content.SharedPreferences.Editor rollback=prefs.edit();if(had)rollback.putBoolean("accepted",prior);else rollback.remove("accepted");rollback.commit();
                Toast.makeText(this,"无法保存选择，请重试",Toast.LENGTH_SHORT).show();return;
            }
            dialog.dismiss();showPage(PAGE_KEYS);
        });
    }
    private void refreshRuleBackup(){if(restoreRulesButton!=null)restoreRulesButton.setVisibility(getSharedPreferences("recognition-rules",MODE_PRIVATE).contains(RULE_BACKUP)?View.VISIBLE:View.GONE);}
    private void exportRulesText(){
        final String content=RuleTextCodec.encode(ruleBook);boolean copied=false;
        try{((ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("映枢按键配置",content));copied=true;}catch(Exception ignored){}
        TextView body=text(content,14,FG,false);body.setTypeface(Typeface.MONOSPACE);body.setTextIsSelectable(true);body.setPadding(dp(20),dp(12),dp(20),dp(12));
        ScrollView scroll = scrollableDialog(body);
        LinearLayout holder = vertical();
        int maxHeight = Math.min(dp(320), getResources().getDisplayMetrics().heightPixels / 2);
        holder.addView(scroll, new LinearLayout.LayoutParams(-1, maxHeight));
        presentDialog(new AlertDialog.Builder(themedContext()).setTitle(copied?"38 项按键已复制":"按键配置文本").setView(holder).setPositiveButton("关闭",null)
            .setNeutralButton("保存 TXT",(dialog,which)->{
                pendingRuleExport=content;Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT);intent.addCategory(Intent.CATEGORY_OPENABLE);intent.setType("text/plain");
                intent.putExtra(Intent.EXTRA_TITLE,"映枢-按键配置.txt");startActivityForResult(intent,REQUEST_RULE_EXPORT);
            }).create());
    }
    private void showRulesImport(String supplied){
        if(!keyQuestionAccepted())return;
        Context context=themedContext();LinearLayout form=new LinearLayout(context);form.setOrientation(LinearLayout.VERTICAL);form.setPadding(dp(20),dp(8),dp(20),0);
        marginAdd(form,text("导入会替换全部 38 项按键。你可以恢复导入前的配置。",14,MUTED,false),0,12);
        TextView inputLabel = text("按键配置文本",14,FG,true);
        marginAdd(form,inputLabel,0,6);
        EditText input=new EditText(context);input.setId(View.generateViewId());inputLabel.setLabelFor(input.getId());input.setGravity(Gravity.TOP);input.setTextSize(14);input.setTypeface(Typeface.MONOSPACE);input.setTextColor(FG);input.setHintTextColor(MUTED);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE|android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setHint("粘贴 YINGSHU_KEYS_V1 配置文本");
        input.setBackgroundTintList(null);
        input.setBackground(controlOutline(ThemePalette.BG, 10, true));
        input.setPadding(dp(12),dp(10),dp(12),dp(10));
        input.setMinLines(3);input.setMaxLines(7);form.addView(input,new LinearLayout.LayoutParams(-1,-2));
        String initial=supplied;
        if(initial==null){try{
            ClipboardManager clipboard=(ClipboardManager)getSystemService(CLIPBOARD_SERVICE);ClipData clip=clipboard.getPrimaryClip();
            if(clip!=null&&clip.getItemCount()>0){CharSequence raw=clip.getItemAt(0).getText();if(raw!=null&&raw.length()<=65536&&raw.toString().replace("\uFEFF","").trim().startsWith("YINGSHU_KEYS_V1"))initial=raw.toString();}
        }catch(Exception ignored){}}
        if(initial!=null)input.setText(initial);
        Button clear=button("清空文本",false);clear.setOnClickListener(v->input.setText(""));marginAdd(form,clear,8,8);
        TextView error=text("",12,WARN,false);error.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);form.addView(error);
        AlertDialog dialog=new AlertDialog.Builder(context).setTitle("导入按键文本").setView(scrollableDialog(form)).setNegativeButton("取消",null).setPositiveButton("导入",null)
            .setNeutralButton("读取 TXT",(d,which)->{Intent open=new Intent(Intent.ACTION_OPEN_DOCUMENT);open.addCategory(Intent.CATEGORY_OPENABLE);open.setType("text/*");startActivityForResult(open,REQUEST_RULE_IMPORT);}).create();
        presentDialog(dialog);dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN|WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        form.setFocusableInTouchMode(true);form.requestFocus();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            try{RuleTextCodec.Parsed parsed=RuleTextCodec.decode(input.getText().toString(),ruleBook);
                if(applyRuleImport(parsed,false)){dialog.dismiss();Toast.makeText(this,"已导入 38 项，已分配 "+parsed.assignedCount+" 项",Toast.LENGTH_LONG).show();}
                else error.setText("保存失败，原配置保留，请重试。");
            }catch(IllegalArgumentException failure){error.setText(failure.getMessage());input.setError(failure.getMessage());input.requestFocus();}
        });
    }
    private boolean applyRuleImport(RuleTextCodec.Parsed parsed,boolean restoring){
        android.content.SharedPreferences prefs=getSharedPreferences("recognition-rules",MODE_PRIVATE);Map<String,?> before=prefs.getAll();
        android.content.SharedPreferences.Editor edit=prefs.edit();
        if(restoring)edit.remove(RULE_BACKUP);else edit.putString(RULE_BACKUP,RuleTextCodec.encode(ruleBook));
        for(RuleTextCodec.Entry entry:parsed.entries){edit.putInt(entry.id+".key",entry.usage);edit.putInt(entry.id+".mod",entry.modifiers);}
        if(!edit.commit()){
            android.content.SharedPreferences.Editor rollback=prefs.edit();
            for(RuleBook.Rule rule:ruleBook.rules)for(String suffix:new String[]{".key",".mod"}){String key=rule.id+suffix;Object old=before.get(key);if(old instanceof Integer)rollback.putInt(key,(Integer)old);else rollback.remove(key);}
            Object backup=before.get(RULE_BACKUP);if(backup instanceof String)rollback.putString(RULE_BACKUP,(String)backup);else rollback.remove(RULE_BACKUP);rollback.commit();return false;
        }
        loadRules();ruleGate.reset();drawRuleRows();updatePreviewRecognition();outputStatus.setText(idleOutputStatus());refreshRuleBackup();
        log(restoring?"RULES_RESTORED count=38":"RULES_IMPORTED count=38");return true;
    }
    private void restoreRulesBackup(){
        String backup=getSharedPreferences("recognition-rules",MODE_PRIVATE).getString(RULE_BACKUP,null);if(backup==null)return;
        final RuleTextCodec.Parsed parsed;
        try{parsed=RuleTextCodec.decode(backup,ruleBook);}catch(IllegalArgumentException error){Toast.makeText(this,"备份无效，原配置未改变",Toast.LENGTH_LONG).show();return;}
        presentDialog(new AlertDialog.Builder(themedContext()).setTitle("恢复导入前配置？").setMessage("将恢复上次导入之前的全部 38 项按键。")
            .setNegativeButton("取消",null).setPositiveButton("恢复",(d,which)->{if(applyRuleImport(parsed,true))Toast.makeText(this,"已恢复导入前配置",Toast.LENGTH_SHORT).show();else Toast.makeText(this,"恢复失败，原配置保留",Toast.LENGTH_LONG).show();}).create());
    }
    private String readRulesText(android.net.Uri uri) throws Exception{
        try(java.io.InputStream input=getContentResolver().openInputStream(uri);java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream()){
            if(input==null)throw new IllegalArgumentException("无法打开文本文件");byte[] buffer=new byte[4096];int n;
            while((n=input.read(buffer))!=-1){if(out.size()+n>65536)throw new IllegalArgumentException("文本文件不能超过 64 KB");out.write(buffer,0,n);}return new String(out.toByteArray(),StandardCharsets.UTF_8);
        }
    }
    private void loadRules() {
        android.content.SharedPreferences prefs=getSharedPreferences("recognition-rules",MODE_PRIVATE);
        for(RuleBook.Rule rule:ruleBook.rules){
            int code=prefs.getInt(rule.id+".key",0);
            rule.usage=KeyCatalog.USAGES[KeyCatalog.indexOf(code)];
            rule.modifiers=prefs.getInt(rule.id+".mod",0)&15;
        }
    }

    private void makeModelCard(LinearLayout parent) {
        LinearLayout controls = modulePanel(UiIcon.KEYBOARD, "识别与按键");
        recognitionSwitch = themedSwitch();
        recognitionSwitch.setText("识别枪械和倍镜");
        recognitionSwitch.setChecked(true);
        addSwitch(controls, recognitionSwitch);
        recognitionSwitch.setOnCheckedChangeListener((v, checked) -> {
            if (ndi != null) ndi.setRecognitionEnabled(checked);
            resetRecognition();
        });
        sectionDivider(controls);
        outputSwitch = themedSwitch();
        outputSwitch.setText("按识别结果发送按键");
        outputSwitch.setChecked(false);
        addSwitch(controls, outputSwitch);
        outputSwitch.setOnCheckedChangeListener((v, checked) -> {
            ruleGate.reset();
            outputStatus.setText(idleOutputStatus());
            log("MAPPED_KEY_OUTPUT " + checked);
        });
        outputStatus = text(idleOutputStatus(), 13, MUTED, false);
        marginAdd(controls, outputStatus, 4, 0);
        marginAdd(parent, controls, 0, 12);
    }

    private void drawRuleRows() {
        ruleRows.removeAllViews();
        String previous = null;
        LinearLayout group = null;
        for (RuleBook.Rule rule : ruleBook.rules) {
            boolean unknown = RuleBook.UNKNOWN_GUN_ID.equals(rule.id);
            if (!rule.gun.equals(previous)) {
                group = card(PanelFrame.Kind.SECTION);
                TextView heading = text(unknown ? "其他情况" : RuleBook.gunName(rule.gun), 19, FG, true);
                if (Build.VERSION.SDK_INT >= 28) heading.setAccessibilityHeading(true);
                marginAdd(group, heading, 0, 8);
                if (unknown) {
                    marginAdd(group, text("无法确定枪型时使用。已识别枪型、倍镜未确定时会等待下一次结果。", 14, MUTED, false), 0, 8);
                }
                marginAdd(ruleRows, group, 0, 16);
                previous = rule.gun;
            }
            sectionDivider(group);
            LinearLayout row = horizontal();
            row.setPaddingRelative(dp(4), dp(8), dp(4), dp(8));
            row.setMinimumHeight(dp(56));
            row.setBackground(themedRipple(controlOutline(ThemePalette.CARD, 8, false), 8, false));
            TextView optic = text(unknown ? "未识别到枪械" : RuleBook.opticName(rule.optic), 15, FG, false);
            row.addView(optic, new LinearLayout.LayoutParams(0, -2, 1));
            String binding = KeyCatalog.name(rule.usage, rule.modifiers);
            TextView key = text(binding, 14, rule.usage == 0 ? MUTED : ACCENT, rule.usage != 0);
            key.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
            row.addView(key, new LinearLayout.LayoutParams(0, -2, 1));
            ImageView chevron = new ImageView(this);
            chevron.setImageDrawable(themedIcon(UiIcon.CHEVRON, MUTED, 16));
            chevron.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            LinearLayout.LayoutParams arrow = new LinearLayout.LayoutParams(dp(16), dp(16));
            arrow.setMarginStart(dp(8));
            row.addView(chevron, arrow);
            row.setContentDescription("设置 " + rule.title() + " 的按键，当前 " + binding);
            row.setFocusable(true);
            row.setOnClickListener(v -> editRule(rule));
            MotionFeedback.attach(row);
            group.addView(row);
        }
    }

    private void editRule(RuleBook.Rule rule) {
        Context editorContext = themedContext();
        LinearLayout form = new LinearLayout(editorContext);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(20), dp(12), dp(20), dp(8));
        int[] selectedUsage = {rule.usage};
        TextView keyLabel = text("主按键", 14, MUTED, true);
        form.addView(keyLabel);
        Button keys = button(KeyCatalog.name(selectedUsage[0], 0), false);
        keys.setId(View.generateViewId());
        keyLabel.setLabelFor(keys.getId());
        keys.setContentDescription("选择主按键，当前 " + KeyCatalog.name(selectedUsage[0], 0));
        keys.setOnClickListener(v -> SearchableKeyPicker.show(themedContext(), selectedUsage[0], BG, CARD, FG, MUTED, ACCENT, BORDER, usage -> {
            selectedUsage[0] = usage;
            String name = KeyCatalog.name(usage, 0);
            keys.setText(name);
            keys.setContentDescription("选择主按键，当前 " + name);
        }));
        marginAdd(form, keys, 8, 16);
        form.addView(text("修饰键", 14, MUTED, true));
        CheckBox[] modifiers = new CheckBox[4];
        String[] titles = {"Ctrl", "Shift", "Alt", "Win"};
        for (int i = 0; i < modifiers.length; i++) {
            CheckBox modifier = new CheckBox(editorContext);
            modifier.setText(titles[i]);
            modifier.setTextSize(15);
            themeText(modifier, FG);
            modifier.setButtonTintList(choiceTint());
            modifier.setChecked((rule.modifiers & (1 << i)) != 0);
            modifiers[i] = modifier;
        }
        marginAdd(form, pairedControls(modifiers[0], modifiers[1]), 4, 0);
        marginAdd(form, pairedControls(modifiers[2], modifiers[3]), 0, 0);
        AlertDialog dialog = new AlertDialog.Builder(editorContext).setTitle(rule.title())
            .setView(scrollableDialog(form)).setNegativeButton("取消", null).setPositiveButton("保存", null).create();
        presentDialog(dialog);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            int usage = selectedUsage[0], mask = 0;
            if (usage != 0) {
                for (int i = 0; i < modifiers.length; i++) if (modifiers[i].isChecked()) mask |= 1 << i;
            }
            boolean saved = getSharedPreferences("recognition-rules", MODE_PRIVATE).edit()
                .putInt(rule.id + ".key", usage).putInt(rule.id + ".mod", mask).commit();
            if (!saved) {
                Toast.makeText(this, "保存失败，请重试", Toast.LENGTH_SHORT).show();
                return;
            }
            rule.usage = usage;
            rule.modifiers = mask;
            ruleGate.reset();
            drawRuleRows();
            updatePreviewRecognition();
            outputStatus.setText(idleOutputStatus());
            log("RULE_SAVED " + rule.id + " " + KeyCatalog.name(usage, mask));
            dialog.dismiss();
        });
    }

    private int assignedRuleCount() {
        int assigned = 0;
        for (RuleBook.Rule rule : ruleBook.rules) if (rule.usage != 0) assigned++;
        return assigned;
    }

    private String idleOutputStatus() {
        int assigned=assignedRuleCount();
        String configured=assigned==0?"输出键均未分配":"已配置 "+assigned+"/"+ruleBook.rules.length+" 项";
        if(!recognitionSwitch.isChecked())return configured+" · 识别已关闭，不发键";
        if(!outputSwitch.isChecked())return configured+" · 自动输出关闭";
        return configured+(videoLive?" · 等待稳定识别与 M3 连接":" · 等待画面，不发键");
    }

    /** Displays the configured mapping only; it is not a key-delivery status. */
    private void updatePreviewRecognition() {
        if(previewRecognition==null)return;
        previewRecognition.setText(lastRecognition+(recognizedRule==null?" · 按键 —":" → "+KeyCatalog.name(recognizedRule.usage,recognizedRule.modifiers)));
        themeText(previewRecognition,recognizedRule==null?MUTED:ACCENT);
    }

    /** Clear both pending work and the visible result when its video/recognition context ends. */
    private void resetRecognition() {
        if(modelEngine!=null)modelEngine.reset();
        clearRecognitionDisplay(recognitionSwitch.isChecked()?"等待画面":"识别已关闭");
    }

    private void clearRecognitionDisplay(String status) {
        ++recognitionDisplayToken;recognitionExpiry.removeCallbacksAndMessages(null);
        clearModelTiming();ruleGate.reset();
        lastRecognition=status;
        recognizedRule=null;updatePreviewRecognition();
        modelResult.setText(lastRecognition);outputStatus.setText(idleOutputStatus());
    }

    private void expireDisplayedResult(long capturedAtMs,long nowMs) {
        final long token=++recognitionDisplayToken;
        recognitionExpiry.removeCallbacksAndMessages(null);
        recognitionExpiry.postDelayed(()->{
            if(destroyed||token!=recognitionDisplayToken)return;
            // Display expiry cannot submit keys. Keep short-gap observation history;
            // every actual submission still requires a new <=500ms normal result.
            lastRecognition=recognitionSwitch.isChecked()?"等待新画面":"识别已关闭";
            recognizedRule=null;updatePreviewRecognition();modelResult.setText(lastRecognition);
            outputStatus.setText("等待新画面，不发键");
        },Math.max(1,501-(nowMs-capturedAtMs)));
    }

    private void receiveFrame(int source,Bitmap frame) {
        if(frame==null)return;
        if(destroyed||!captureRequested||videoMode!=source){frame.recycle();return;}
        // A real frame is sufficient to resume after WAITING, even if a backend only
        // announces RUNNING once. Never gate recovery on the previously stale state.
        if(!videoLive){
            videoLive=true;
            refreshDrawerStatus();
            String detail="已收到真实 "+(source==0?"USB":"NDI")+" 视频帧："+frame.getWidth()+" × "+frame.getHeight();
            lastVideo="RUNNING | "+detail;videoState.setText(detail);themeText(videoState,ACCENT);
        }
        if(recognitionSwitch.isChecked()&&modelEngine!=null)modelEngine.offer(frame);
        videoPreview.showFrame(frame);
    }

    private void receiveNdiFrame(NdiReceiver.Frame frame) {
        if(frame==null)return;
        if(destroyed||!captureRequested||videoMode!=1){frame.recycle();return;}
        if(!videoLive){
            videoLive=true;
            refreshDrawerStatus();
            String detail="已收到真实 NDI 视频帧："+frame.sourceWidth+" × "+frame.sourceHeight;
            lastVideo="RUNNING | "+detail;videoState.setText(detail);themeText(videoState,ACCENT);
        }
        try {
            if(recognitionSwitch.isChecked()&&modelEngine!=null
                    &&(frame.panel!=null||frame.sourceWidth!=1920||frame.sourceHeight!=1080))
                modelEngine.offerPanel(frame.panel,frame.sourceWidth,frame.sourceHeight,frame.receivedAtMs);
        } finally {
            if(frame.panel!=null&&!frame.panel.isRecycled())frame.panel.recycle();
        }
        // The preview owns this Bitmap after delivery; never recycle an image still being drawn.
        if(frame.preview!=null)videoPreview.showFrame(frame.preview);
    }

    private void receiveRecognition(ModelEngine.Result result) {
        if(destroyed)return;
        long now=android.os.SystemClock.elapsedRealtime();
        if(!RecognitionRuleSelector.isUsableResult(result!=null,captureRequested&&videoLive,
                recognitionSwitch.isChecked(),result==null?-1:result.capturedAtMs,now)){
            clearRecognitionDisplay(recognitionSwitch.isChecked()?"等待新画面":"识别已关闭");return;
        }
        if(modelTimingStart==0)modelTimingStart=now;
        modelTimingCount++;modelTimingPre+=result.preprocessMs;modelTimingRun+=result.modelMs;
        modelTimingPost+=result.postprocessMs;modelTimingAge+=Math.max(0,now-result.capturedAtMs);
        if(now-modelTimingStart>=5000){
            log(String.format(Locale.ROOT,"MODEL_PROFILE joint runs=%d resultFps=%.2f preprocessAvgMs=%.2f runAvgMs=%.2f postAvgMs=%.2f localFrameToResultAvgMs=%.2f (not end-to-end)",
                modelTimingCount,(modelTimingCount-1)*1000.0/(now-modelTimingStart),
                modelTimingPre/modelTimingCount,modelTimingRun/modelTimingCount,modelTimingPost/modelTimingCount,modelTimingAge/modelTimingCount));
            clearModelTiming();
        }
        String gun=result.gun==null?"":result.gun;
        String loadedStatus="八枪模型已加载 · 38 项按键规则";
        if(!loadedStatus.contentEquals(modelStatus.getText()))modelStatus.setText(loadedStatus);
        lastRecognition=gun.isEmpty()||"unknown".equals(gun)?"未识别到枪械":RuleBook.gunName(gun)+" · "+RuleBook.opticName(result.optic);
        String gunScore=gun.isEmpty()||"unknown".equals(gun)?"--":String.format(Locale.ROOT,"%.0f%%",result.gunConfidence*100);
        String opticScore="unknown".equals(result.optic)?"--":String.format(Locale.ROOT,"%.0f%%",result.opticConfidence*100);
        modelResult.setText(lastRecognition+String.format(Locale.ROOT,"\n置信：枪 %s · 镜 %s · 推理 %.0f ms",gunScore,opticScore,result.elapsedMs));
        RuleBook.Rule rule=RecognitionRuleSelector.select(ruleBook,gun,result.optic,true,
                captureRequested&&videoLive,recognitionSwitch.isChecked(),result.capturedAtMs,now);
        recognizedRule=rule;updatePreviewRecognition();
        expireDisplayedResult(result.capturedAtMs,now);
        if(!outputSwitch.isChecked()){ruleGate.reset();outputStatus.setText("发键已关闭 · "+(rule==null?"无匹配规则":KeyCatalog.name(rule.usage,rule.modifiers)));return;}
        if(rule==null||rule.usage==0||now-result.capturedAtMs>500||!ble.isReady()){
            ruleGate.observe(null,now);
            outputStatus.setText(rule==null?"等待确定的枪械与倍镜":rule.usage==0?"该组合尚未分配按键":!ble.isReady()?"等待 M3 完成连接":"等待更新的识别画面");return;
        }
        String binding=rule.bindingId();
        if(ruleGate.observe(binding,now)){
            if(ble.sendMappedKey(rule.usage,rule.modifiers)){
                ruleGate.submitted(binding);
                outputStatus.setText("已提交："+rule.title()+" → "+KeyCatalog.name(rule.usage,rule.modifiers));
                log("MAPPED_KEY_QUEUED "+binding+" (downstream execution unverified)");
            }else outputStatus.setText(ble.isReady()?"等待上一组按键发送完成":"M3 连接未就绪，未提交按键");
        }else if(!ruleGate.isSubmitted(binding))outputStatus.setText("确认中："+rule.title());
    }

    private void clearModelTiming(){
        modelTimingStart=modelTimingCount=0;
        modelTimingPre=modelTimingRun=modelTimingPost=modelTimingAge=0;
    }

    private void makeVideoSourcePage(LinearLayout parent) {
        LinearLayout transport = modulePanel(UiIcon.VIDEO, "输入方式");
        videoKind = themedSpinner();
        videoKind.setAdapter(themedAdapter(java.util.Arrays.asList("USB · 电脑画面（采集卡）", "NDI · 局域网视频")));
        videoKind.setSelection(videoMode);
        videoKind.setContentDescription("输入方式");
        videoKind.setMinimumHeight(dp(48));
        marginAdd(transport, videoKind, 8, 0);

        ndiOptions = vertical();
        ndiOptions.setVisibility(View.GONE);
        TextView hostLabel = text("发送端 IP（可选）", 14, FG, true);
        ndiOptions.addView(hostLabel);
        ndiHost = new EditText(themedContext());
        ndiHost.setId(View.generateViewId());
        hostLabel.setLabelFor(ndiHost.getId());
        ndiHost.setSingleLine(true);
        ndiHost.setMinHeight(dp(48));
        ndiHost.setTextSize(15);
        themeText(ndiHost, FG);
        ndiHost.setHint("自动发现失败时填写");
        ndiHost.setBackgroundTintList(null);
        ndiHost.setBackground(controlOutline(ThemePalette.BG, 10, true));
        ndiHost.setPadding(dp(12), dp(8), dp(12), dp(8));
        // This field survives a manual theme switch, unlike the short-lived dialog editors.
        int lightHighlight = selectionHighlight(false);
        int darkHighlight = selectionHighlight(true);
        palette.bind(ndiHost, (field, p) -> {
            field.setHintTextColor(p.color(ThemePalette.MUTED));
            field.setHighlightColor(ThemePalette.blend(lightHighlight, darkHighlight, p.progress()));
        });
        if (Build.VERSION.SDK_INT >= 29 && ndiHost.getTextCursorDrawable() != null) {
            android.graphics.drawable.Drawable cursor = ndiHost.getTextCursorDrawable().mutate();
            ndiHost.setTextCursorDrawable(cursor);
            palette.bind(cursor, (drawable, p) -> drawable.setTint(p.color(ThemePalette.ACCENT)));
        }
        ndiHost.setText(getPreferences(MODE_PRIVATE).getString("ndiHost", ""));
        marginAdd(ndiOptions, ndiHost, 8, 0);
        TextView ndiLink = text("NDI®  ·  ndi.video ↗", 14, ACCENT, false);
        ndiLink.setGravity(Gravity.CENTER_VERTICAL);
        ndiLink.setMinimumHeight(dp(48));
        ndiLink.setFocusable(true);
        ndiLink.setBackground(themedRipple(controlOutline(ThemePalette.CARD, 8, false), 8, false));
        ndiLink.setOnClickListener(v -> startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://ndi.video/"))));
        ndiOptions.addView(ndiLink);
        ndiOptions.addView(text("NDI® is a registered trademark of Vizrt NDI AB.", 12, MUTED, false));
        marginAdd(transport, ndiOptions, 12, 0);
        marginAdd(parent, transport, 0, 16);

        LinearLayout sources = modulePanel(UiIcon.DEVICE, "可用视频源");
        videoSources = themedSpinner();
        videoSources.setContentDescription("可用视频源");
        videoSources.setMinimumHeight(dp(48));
        marginAdd(sources, videoSources, 8, 8);
        refreshVideoButton = button("刷新视频源", false);
        refreshVideoButton.setOnClickListener(v -> refreshSources());
        sourceRunButton = button("开始接收", true);
        sourceRunButton.setOnClickListener(v -> {
            if (captureRequested) stopVideo("已停止");
            else startVideo();
        });
        sources.addView(pairedControls(refreshVideoButton, sourceRunButton));
        marginAdd(sources, text("要切换输入，请先停止当前接收。", 13, MUTED, false), 12, 0);
        marginAdd(parent, sources, 0, 16);
        Button back = button("查看识别画面", false);
        back.setOnClickListener(v -> showPage(PAGE_HOME));
        marginAdd(parent, back, 0, 0);
    }

    private void bindVideoControls() {
        previewSwitch.setOnCheckedChangeListener((v,checked)->{
            getPreferences(MODE_PRIVATE).edit().putBoolean("preview",checked).apply();videoPreview.setPreview(checked);
            if(uvc!=null)uvc.setPreviewEnabled(checked);if(ndi!=null)ndi.setPreviewEnabled(checked);
            log("PREVIEW "+checked+"; receive continues while running");
        });
        ecoSwitch.setOnCheckedChangeListener((v,checked)->{
            getPreferences(MODE_PRIVATE).edit().putBoolean("eco",checked).apply();
            if(uvc!=null)uvc.setEco(checked);if(ndi!=null)ndi.setEco(checked);log("ECO "+checked);
        });
        videoKind.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onNothingSelected(AdapterView<?> view) { }
            @Override public void onItemSelected(AdapterView<?> view,View row,int position,long id) {
                if(uvc==null||ndi==null||usb==null)return;
                if(captureRequested)return;
                if(videoMode!=position) { uvc.stop();ndi.stop();videoMode=position; }
                String sourceName=videoMode==0?"USB":"NDI";
                sourceTag.setText(sourceName);sourceTag.setContentDescription("视频输入："+sourceName);
                getPreferences(MODE_PRIVATE).edit().putInt("videoMode",position).apply();
                ndiOptions.setVisibility(position==1?View.VISIBLE:View.GONE);
                videoState.setText("未启动");videoStats.setText("刷新视频源后开始接收");videoPreview.receiving(false);videoPreview.clear("选择视频源，开始接收");
                updateSourceChoices();refreshSources();
            }
        });
        videoPreview.setPreview(previewSwitch.isChecked());
    }

    private void updateSourceChoices() {
        if(videoSources==null||captureRequested)return;
        int previousIndex=videoSources.getSelectedItemPosition();
        String previous=previousIndex>=0&&previousIndex<displayedSourceIds.size()?displayedSourceIds.get(previousIndex):"";
        ArrayList<String> names=new ArrayList<>();
        displayedSourceIds.clear();
        if(videoMode==0)for(UsbProbe.DeviceInfo item:usbChoices){names.add(item.name+String.format(Locale.ROOT,"  [%04X:%04X]",item.vendorId,item.productId));displayedSourceIds.add("usb:"+item.id);}
        else for(NdiReceiver.Source item:ndiChoices){names.add(item.name);displayedSourceIds.add("ndi:"+item.name+"|"+item.urlAddress);}
        if(names.isEmpty())names.add(videoMode==0?"未发现采集卡，请通过 OTG 接入":"尚未发现 NDI 源，请刷新");
        videoSources.setAdapter(themedAdapter(names));
        int selected=displayedSourceIds.indexOf(previous);if(selected>=0)videoSources.setSelection(selected);
        updateVideoControls();
    }
    private void refreshSources() {
        if(captureRequested)return;
        if(videoMode==0) { if(usb!=null)usb.refresh(); }
        else if(ndi!=null) { String host=ndiHost.getText().toString().trim();getPreferences(MODE_PRIVATE).edit().putString("ndiHost",host).apply();ndi.discover(host); }
    }
    private void startVideo() {
        if(captureRequested)return;
        int index=videoSources.getSelectedItemPosition();
        String selection=index>=0&&index<displayedSourceIds.size()?displayedSourceIds.get(index):"";
        UsbProbe.DeviceInfo selectedUsb=null;NdiReceiver.Source selectedNdi=null;
        for(UsbProbe.DeviceInfo item:usbChoices)if(selection.equals("usb:"+item.id))selectedUsb=item;
        for(NdiReceiver.Source item:ndiChoices)if(selection.equals("ndi:"+item.name+"|"+item.urlAddress))selectedNdi=item;
        if(videoMode==0&&selectedUsb==null) { Toast.makeText(this,"请先接入并刷新采集卡",Toast.LENGTH_SHORT).show();refreshSources();return; }
        if(videoMode==1&&selectedNdi==null) { Toast.makeText(this,"请先发现并选择 NDI 视频源",Toast.LENGTH_SHORT).show();refreshSources();return; }
        captureRequested=true;videoLive=false;resetRecognition();updateVideoControls();videoPreview.receiving(true);videoPreview.clear("正在连接视频源…");videoState.setText("正在准备");videoStats.setText("等待首帧…");
        if(videoMode==0) { pendingVideoUsb=true;openUsb(selectedUsb.id); }
        else { ndi.setPreviewEnabled(previewSwitch.isChecked());ndi.setEco(ecoSwitch.isChecked());ndi.connect(selectedNdi); }
    }
    private void stopVideo(String reason) {
        captureRequested=false;pendingVideoUsb=false;pendingUsb=-1;
        videoLive=false;resetRecognition();
        if(uvc!=null)uvc.stop();if(ndi!=null)ndi.stop();
        if(videoState!=null) { videoState.setText(reason);videoStats.setText("视频输入已停止");videoPreview.receiving(false);videoPreview.clear("已停止接收");updateVideoControls();updateSourceChoices(); }
        lastVideo=reason;
    }
    private void updateVideoControls() {
        refreshDrawerStatus();
        boolean hasSource=!displayedSourceIds.isEmpty();
        runVideoButton.setText(captureRequested?"停止接收":hasSource?"开始接收":"选择视频源");
        changeVideoSource.setVisibility(hasSource&&!captureRequested?View.VISIBLE:View.GONE);
        sourceRunButton.setText(captureRequested?"停止接收":"开始接收");
        sourceRunButton.setEnabled(captureRequested||hasSource);
        videoKind.setEnabled(!captureRequested);videoSources.setEnabled(!captureRequested);
        refreshVideoButton.setEnabled(!captureRequested);ndiHost.setEnabled(!captureRequested);
    }
    private void captureState(int source,String state,String detail) {
        if(destroyed)return;log((source==0?"UVC_STATE ":"NDI_STATE ")+state+" | "+detail);
        if(source!=videoMode)return;
        // Explicit stopVideo already synchronously owns STOPPED. A delayed backend
        // STOPPED from a previous source must not cancel a newer USB permission request.
        if("STOPPED".equals(state))return;
        boolean receivingState="RUNNING".equals(state)||"WAITING".equals(state)||"STARTING".equals(state)||"CONNECTING".equals(state);
        if(receivingState&&!captureRequested)return;
        // RUNNING reports transport progress; only receiveFrame can make inference live.
        if(!"RUNNING".equals(state)){videoLive=false;resetRecognition();}
        if("ERROR".equals(state)||"PERMISSION_REQUIRED".equals(state)) {
            captureRequested=false;pendingVideoUsb=false;pendingUsb=-1;
            videoPreview.receiving(false);videoPreview.clear("ERROR".equals(state)?"未能接收视频":"已停止接收");updateVideoControls();updateSourceChoices();
        } else if(receivingState) {
            videoPreview.receiving(true);
            if("WAITING".equals(state))videoPreview.clear("等待发送端画面…");
        } else if(captureRequested)return;
        lastVideo=state+" | "+detail;videoState.setText(detail);themeText(videoState,"RUNNING".equals(state)?ACCENT:("ERROR".equals(state)?WARN:FG));
        refreshDrawerStatus();
    }
    private void captureStats(int source,String stats) {
        if(destroyed||!captureRequested||source!=videoMode)return;
        videoStats.setText(stats);
        long now=android.os.SystemClock.elapsedRealtime();
        if(now-lastStatsLog>=5000) {lastStatsLog=now;log((source==0?"UVC_STATS ":"NDI_STATS ")+stats);}
    }

    private void scan() {
        if ("CONNECTING".equals(phase)||"DISCOVERING".equals(phase)||"SUBSCRIBING".equals(phase)||"HANDSHAKING".equals(phase)||"READY".equals(phase)) return;
        List<String> needed = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 31) {
            if(checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)!=PackageManager.PERMISSION_GRANTED) needed.add(Manifest.permission.BLUETOOTH_SCAN);
            if(checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED) needed.add(Manifest.permission.BLUETOOTH_CONNECT);
        } else if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED) needed.add(Manifest.permission.ACCESS_FINE_LOCATION);
        if(!needed.isEmpty()) { requestPermissions(needed.toArray(new String[0]),REQUEST_BLE); return; }
        BluetoothAdapter adapter=BluetoothAdapter.getDefaultAdapter();
        if(adapter==null) { log("设备没有蓝牙适配器"); return; }
        if(!adapter.isEnabled()) { startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE),REQUEST_ENABLE_BT); return; }
        if(Build.VERSION.SDK_INT < 31) {
            LocationManager locations=(LocationManager)getSystemService(LOCATION_SERVICE);
            boolean enabled=locations!=null && (locations.isProviderEnabled(LocationManager.GPS_PROVIDER)||locations.isProviderEnabled(LocationManager.NETWORK_PROVIDER));
            if(!enabled) { presentDialog(new AlertDialog.Builder(themedContext()).setTitle("需要开启定位开关").setMessage("此 Android 版本要求开启定位开关才能扫描蓝牙。映枢不读取位置数据。")
                .setNegativeButton("取消",null).setPositiveButton("打开设置",(d,w)->startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))).create());return; }
        }
        discovered.clear();drawBleDevices();ble.startScan();
    }

    private void drawBleDevices() {
        if(bleDevices==null||destroyed)return;
        bleDevices.removeAllViews();
        if(discovered.isEmpty()) {
            bleDevices.addView(text("SCANNING".equals(phase)?"正在搜索，请保持 M3 通电…":"扫描后在此选择设备。",12,MUTED,false));return;
        }
        for(Map.Entry<String,String[]> entry:discovered.entrySet()) {
            final String address=entry.getKey();String[] data=entry.getValue();
            LinearLayout row=vertical();row.setPadding(dp(12),dp(10),dp(12),dp(10));row.setBackground(roleShape(ThemePalette.BG,10));
            row.addView(text(data[0]+"  ·  "+data[1]+" dBm",14,FG,true));
            marginAdd(row,text(address,11,MUTED,false),4,7);
            boolean busy="CONNECTING".equals(phase)||"DISCOVERING".equals(phase)||"SUBSCRIBING".equals(phase)||"HANDSHAKING".equals(phase)||"READY".equals(phase);
            Button connect=button(busy&&address.equals(selectedAddress)?("READY".equals(phase)?"握手成功":"连接中…"):"连接此设备",false);
            connect.setEnabled(!busy);
            connect.setOnClickListener(v->{ selectedAddress=address;ble.connect(address); });
            row.addView(connect,new LinearLayout.LayoutParams(-1,-2));marginAdd(bleDevices,row,8,0);
        }
    }

    private void drawUsbDevices(List<UsbProbe.DeviceInfo> devices) {
        if(destroyed)return;usbDevices.removeAllViews();
        for(UsbProbe.DeviceInfo info:devices) {
            LinearLayout row=vertical();row.setPadding(dp(12),dp(10),dp(12),dp(10));row.setBackground(roleShape(ThemePalette.BG,10));
            row.addView(text(info.name,14,FG,true));marginAdd(row,text(info.description.split("\n",2)[0]+(info.hasPermission?" · 已授权":" · 未授权"),11,MUTED,false),5,5);
            if(info.uvc) {
                Button open=button("授权并检查连接",false);open.setOnClickListener(v -> {
                    if(captureRequested)Toast.makeText(this,"请先停止视频，再单独检查 USB 设备",Toast.LENGTH_SHORT).show();
                    else openUsb(info.id);
                });
                row.addView(open,new LinearLayout.LayoutParams(-1,-2));
            } else marginAdd(row,text("不是 UVC 视频设备",11,MUTED,false),4,0);
            marginAdd(usbDevices,row,6,0);
        }
    }

    private void openUsb(int deviceId) {
        pendingUsb=deviceId;
        if(Build.VERSION.SDK_INT>=28 && checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED) {
            presentDialog(new AlertDialog.Builder(themedContext()).setTitle("外接视频设备权限")
                .setMessage("Android 将 USB 视频采集卡也归为相机设备，因此打开它需要“相机”权限。\n\n映枢仅接收外接采集卡画面，不调用手机前置或后置摄像头。")
                .setNegativeButton("取消",(d,w)->{pendingUsb=-1;if(pendingVideoUsb)stopVideo("权限申请已取消");})
                .setPositiveButton("继续授权",(d,w)->requestPermissions(new String[]{Manifest.permission.CAMERA},REQUEST_CAMERA)).create());
        } else usb.requestOpen(deviceId);
    }

    @Override public void onRequestPermissionsResult(int code,String[] permissions,int[] results) {
        super.onRequestPermissionsResult(code,permissions,results);
        if(!appInitialized||destroyed||isFinishing())return;
        boolean ok=results.length>0;for(int result:results)if(result!=PackageManager.PERMISSION_GRANTED)ok=false;
        if(code==REQUEST_BLE) { if(ok)scan();else { log("蓝牙扫描权限未获允许");bleDetail.setText("扫描权限未获允许，请再次点击扫描并授权。"); } }
        if(code==REQUEST_CAMERA) { if(ok&&pendingUsb>=0)usb.requestOpen(pendingUsb);else {log("外接 USB 视频权限未获允许或请求已取消");if(pendingVideoUsb)stopVideo("未取得视频设备权限");} }
    }

    private void exportReport() {
        Intent create=new Intent(Intent.ACTION_CREATE_DOCUMENT);create.addCategory(Intent.CATEGORY_OPENABLE);create.setType("text/plain");
        create.putExtra(Intent.EXTRA_TITLE,"映枢-硬件诊断.txt");startActivityForResult(create,REQUEST_EXPORT);
    }
    @Override public void onActivityResult(int code,int result,Intent data) {
        super.onActivityResult(code,result,data);
        if(!appInitialized||destroyed||isFinishing()){pendingRuleExport=null;return;}
        if(code==REQUEST_RULE_EXPORT){
            String content=pendingRuleExport;pendingRuleExport=null;
            if(result==RESULT_OK&&data!=null&&data.getData()!=null&&content!=null){
                try(OutputStream stream=getContentResolver().openOutputStream(data.getData())){if(stream==null)throw new IllegalStateException("无法打开文件");stream.write(content.getBytes(StandardCharsets.UTF_8));Toast.makeText(this,"按键文本已保存",Toast.LENGTH_SHORT).show();}
                catch(Exception failure){Toast.makeText(this,"保存失败："+failure.getMessage(),Toast.LENGTH_LONG).show();}
            }return;
        }
        if(code==REQUEST_RULE_IMPORT){
            if(result==RESULT_OK&&data!=null&&data.getData()!=null&&appInitialized&&keyQuestionAccepted()){
                try{showRulesImport(readRulesText(data.getData()));}catch(Exception failure){Toast.makeText(this,"读取失败："+failure.getMessage(),Toast.LENGTH_LONG).show();}
            }return;
        }
        if(code==REQUEST_ENABLE_BT && result==RESULT_OK)scan();
        if(code==REQUEST_EXPORT && result==RESULT_OK && data!=null && data.getData()!=null) {
            try(OutputStream stream=getContentResolver().openOutputStream(data.getData())) {
                if(stream==null)throw new IllegalStateException("无法打开文件");stream.write(fullReport().getBytes(StandardCharsets.UTF_8));
                Toast.makeText(this,"诊断记录已导出",Toast.LENGTH_SHORT).show();
            } catch(Exception e) { log("导出失败："+e.getMessage()); }
        }
    }

    private String summary() { return "映枢 0.3.14-trial · 八枪与倍镜\n设备："+Build.MANUFACTURER+" "+Build.MODEL+" / Android "+Build.VERSION.RELEASE+"\n视频："+lastVideo+"\n统计："+videoStats.getText()+"\n预览："+previewSwitch.isChecked()+"\n节能："+ecoSwitch.isChecked()+"\nM3："+stateName(phase)+"\n地址："+selectedAddress+"\n序列号："+serial+"\nUSB："+lastUsb+"\n模型："+modelStatus.getText()+"\n识别："+lastRecognition+"\n规则发键："+outputSwitch.isChecked()+"\n"; }
    private String fullReport() { return summary()+"\n诊断日志\n"+String.join("\n",logs)+"\n"; }
    private void log(String message) {
        if(destroyed)return;
        String line=new SimpleDateFormat("HH:mm:ss.SSS",Locale.ROOT).format(new Date())+" "+message;
        android.util.Log.i("YingShu",message);logs.add(line);if(logs.size()>250)logs.remove(0);
        if(logView!=null) { int from=Math.max(0,logs.size()-16);logView.setText(String.join("\n",logs.subList(from,logs.size()))); }
        try(FileOutputStream output=new FileOutputStream(sessionLog,true)) { output.write((line+"\n").getBytes(StandardCharsets.UTF_8)); }catch(Exception ignored) { }
    }
    private String stateName(String p) {
        switch(p) { case "SCANNING":return "正在扫描";case "CONNECTING":return "正在连接";case "DISCOVERING":return "正在发现服务";case "SUBSCRIBING":return "正在订阅通知";case "HANDSHAKING":return "等待设备握手";case "READY":return "握手成功";case "DISCONNECTED":return "已断开";case "ERROR":return "连接未完成";default:return "等待连接"; }
    }
    private String usbName(String p) {
        switch(p) { case "NO_DEVICE":return "未发现 USB 设备";case "FOUND":return "发现 USB 设备";case "OPENED":return "USB 设备可打开";case "PERMISSION_REQUIRED":case "CAMERA_PERMISSION_REQUIRED":return "等待授权";case "PERMISSION_DENIED":return "授权未通过";case "OPENING":return "正在检查连接";case "NOT_UVC":return "不是视频设备";case "UNSUPPORTED":return "不支持 USB Host";default:return "USB 检查未完成"; }
    }
    private String compactUsbDetail(String status,String detail) {
        if(!"OPENED".equals(status))return detail;
        StringBuilder out=new StringBuilder("外接设备可打开，已读取描述符。测试句柄已释放。\n");
        for(String line:detail.split("\n"))if(line.startsWith("声明格式：")||line.startsWith("声明尺寸："))out.append(line.length()>240?line.substring(0,240)+"…":line).append('\n');
        return out.append("上述为设备声明能力。实时画面请在“识别”页开始接收。").toString();
    }
    private String hex(byte[] bytes) { StringBuilder s=new StringBuilder();for(byte b:bytes){if(s.length()>0)s.append(' ');s.append(String.format(Locale.ROOT,"%02X",b&255));}return s.toString(); }
    private int dp(float value) { return (int)(value*getResources().getDisplayMetrics().density+0.5f); }
    private LinearLayout vertical() { LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.VERTICAL);return v; }
    private LinearLayout horizontal() { LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.HORIZONTAL);v.setGravity(Gravity.CENTER_VERTICAL);return v; }
    private void themeText(TextView view,int color){palette.text(view,color);}
    private TextView text(String value,int size,int color,boolean bold) { TextView v=new TextView(this);v.setText(value);v.setTextSize(size);themeText(v,color);v.setLineSpacing(dp(3),1);if(bold)v.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));return v; }
    private GradientDrawable shape(int fill,int radius,int border) {
        GradientDrawable d=new LiveGradientDrawable();d.setCornerRadius(dp(radius));
        ThemePalette.Ref fillRef=palette.ref(fill),borderRef=palette.ref(border);int width=dp(1);
        palette.bind(d,(shape,p)->{shape.setColor(fillRef.color(p));if(border!=0)shape.setStroke(width,borderRef.color(p));});return d;
    }
    private GradientDrawable roleShape(int role,int radius){GradientDrawable d=new LiveGradientDrawable();d.setCornerRadius(dp(radius));palette.bind(d,(shape,p)->shape.setColor(p.color(role)));return d;}
    private GradientDrawable fixedShape(int color,int radius){GradientDrawable d=new GradientDrawable();d.setCornerRadius(dp(radius));d.setColor(color);return d;}
    private PanelFrame panelFrame(PanelFrame.Kind kind) {
        return new PanelFrame(kind, getResources().getDisplayMetrics().density, palette);
    }

    private LinearLayout card(PanelFrame.Kind kind) {
        LinearLayout panel=vertical();
        panel.setBackground(panelFrame(kind));
        panel.setPadding(dp(16),dp(16),dp(16),dp(16));
        return panel;
    }
    private void label(LinearLayout parent,String title) {
        LinearLayout heading=horizontal();
        View line=new View(this);
        line.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        line.setBackground(roleShape(ThemePalette.VIOLET,2));
        LinearLayout.LayoutParams mark=new LinearLayout.LayoutParams(dp(3),dp(15));
        mark.setMarginEnd(dp(8));heading.addView(line,mark);
        TextView headingText=text(title,16,FG,true);
        if(Build.VERSION.SDK_INT>=28)headingText.setAccessibilityHeading(true);
        heading.addView(headingText);parent.addView(heading);
    }
    private StateListDrawable controlOutline(int fillRole, int radius, boolean showIdleEdge) {
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{-android.R.attr.state_enabled}, roleShape(ThemePalette.BUTTON_DISABLED, radius));
        GradientDrawable focus = roleShape(fillRole, radius);
        palette.bind(focus, (shape, p) -> shape.setStroke(dp(2), p.color(ThemePalette.ACCENT_TEXT)));
        states.addState(new int[]{android.R.attr.state_focused}, focus);
        GradientDrawable idle = roleShape(fillRole, radius);
        if (showIdleEdge) {
            palette.bind(idle, (shape, p) -> shape.setStroke(dp(1), p.color(ThemePalette.SWITCH_OFF)));
        }
        states.addState(new int[]{}, idle);
        return states;
    }
    private android.graphics.drawable.RippleDrawable themedRipple(android.graphics.drawable.Drawable content,int radius,boolean primary){
        android.graphics.drawable.RippleDrawable d=new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x16000000),content,fixedShape(Color.WHITE,radius));
        palette.bind(d,(r,p)->r.setColor(android.content.res.ColorStateList.valueOf(p.color(primary?ThemePalette.BUTTON_RIPPLE:ThemePalette.RIPPLE))));return d;
    }
    private UiIcon themedIcon(int kind,int color,int size){UiIcon icon=new UiIcon(kind,color,dp(size));ThemePalette.Ref ref=palette.ref(color);palette.bind(icon,(i,p)->i.setColor(ref.color(p)));return icon;}
    private ElasticSwitch themedSwitch(){
        ElasticSwitch view=new ElasticSwitch(this,null,palette);
        themeText(view,FG);
        return view;
    }
    private int selectionHighlight(boolean dark) {
        android.content.res.TypedArray colors = themedContext(dark).obtainStyledAttributes(
                new int[]{android.R.attr.textColorHighlight});
        try { return colors.getColor(0, Color.TRANSPARENT); }
        finally { colors.recycle(); }
    }
    private android.content.res.ColorStateList choiceTint() {
        return new android.content.res.ColorStateList(
                new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}}, new int[]{ACCENT, MUTED});
    }
    private Spinner themedSpinner(){
        Spinner view=new Spinner(themedContext());view.setPopupBackgroundDrawable(roleShape(ThemePalette.BG,12));
        palette.bind(view,(s,p)->s.setBackgroundTintList(android.content.res.ColorStateList.valueOf(p.color(ThemePalette.MUTED))));return view;
    }
    private ArrayAdapter<String> themedAdapter(List<String> values){
        return new ArrayAdapter<String>(themedContext(),android.R.layout.simple_spinner_dropdown_item,values){
            private View colorRow(View view,boolean dropdown){
                if (view instanceof TextView) {
                    TextView label = (TextView) view;
                    themeText(label, FG);
                    if (dropdown) {
                        label.setBackground(roleShape(ThemePalette.BG, 0));
                        if (label instanceof android.widget.CheckedTextView)
                            ((android.widget.CheckedTextView) label).setCheckMarkTintList(choiceTint());
                    }
                }
                return view;
            }
            @Override public View getView(int position,View convertView,ViewGroup parent){return colorRow(super.getView(position,convertView,parent),false);}
            @Override public View getDropDownView(int position,View convertView,ViewGroup parent){return colorRow(super.getDropDownView(position,convertView,parent),true);}
        };
    }
    private ImageButton navigationButton(int kind, String description) {
        ImageButton button = new ImageButton(this);
        NavigationGlyph glyph = new NavigationGlyph(palette, kind, dp(23));
        boolean menu = kind == NavigationGlyph.MENU;
        glyph.setSelection(menu ? 1 : 0);
        button.setImageDrawable(glyph);
        button.setScaleType(ImageView.ScaleType.CENTER);
        button.setContentDescription(description);
        button.setTooltipText(description);
        button.setFocusable(true);
        int radius = menu ? 11 : 24;
        button.setBackground(themedRipple(controlOutline(menu ? ThemePalette.SELECTED : ThemePalette.CARD,
                radius, false), radius, false));
        MotionFeedback.attach(button);
        return button;
    }
    private Button button(String title,boolean primary) {
        Button b=new Button(this);b.setText(title);b.setTextSize(15);b.setAllCaps(false);
        b.setMinWidth(0);b.setMinimumWidth(0);b.setMinHeight(dp(48));b.setMinimumHeight(dp(48));
        b.setPadding(dp(12),dp(8),dp(12),dp(8));
        styleActionButton(b,primary);return b;
    }
    private void styleActionButton(Button b,boolean primary){
        b.setBackgroundTintList(null);
        b.setAllCaps(false);b.setStateListAnimator(null);b.setElevation(0);
        b.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
        final int[][] enabledStates={new int[]{-android.R.attr.state_enabled},new int[]{}};
        palette.bind(b,(button,p)->button.setTextColor(new android.content.res.ColorStateList(enabledStates,new int[]{p.color(ThemePalette.MUTED),p.color(primary?ThemePalette.ON_ACCENT:ThemePalette.FG)})));
        int radius = 11;
        Drawable surface = primary ? primaryButtonFrame(radius) :
                controlOutline(ThemePalette.BUTTON_BOTTOM, radius, true);
        b.setBackground(themedRipple(surface, radius, primary));
        MotionFeedback.attach(b);
    }

    private StateListDrawable primaryButtonFrame(int radius) {
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{-android.R.attr.state_enabled}, roleShape(ThemePalette.BUTTON_DISABLED, radius));
        // The inner ring sits entirely on the gradient, so it can use the same high-contrast ink as the label.
        GradientDrawable ring = new LiveGradientDrawable();
        ring.setCornerRadius(dp(radius - 3));
        ring.setColor(Color.TRANSPARENT);
        palette.bind(ring, (shape, p) -> shape.setStroke(dp(2), p.color(ThemePalette.ON_ACCENT)));
        GradientDrawable idle = primaryButtonFill(radius);
        LayerDrawable focused = new LayerDrawable(new Drawable[]{primaryButtonFill(radius), ring});
        int inset = dp(3);
        focused.setLayerInset(1, inset, inset, inset, inset);
        states.addState(new int[]{android.R.attr.state_focused}, focused);
        states.addState(new int[]{}, idle);
        return states;
    }

    private GradientDrawable primaryButtonFill(int radius) {
        GradientDrawable surface = new LiveGradientDrawable();
        surface.setOrientation(GradientDrawable.Orientation.LEFT_RIGHT);
        surface.setCornerRadius(dp(radius));
        palette.bind(surface, (shape,p) -> shape.setColors(new int[]{
                p.color(ThemePalette.ACCENT), p.color(ThemePalette.ACCENT_END)}));
        return surface;
    }
    private void presentDialog(AlertDialog dialog){
        dialog.show();
        for(int which:new int[]{AlertDialog.BUTTON_NEGATIVE,AlertDialog.BUTTON_POSITIVE,AlertDialog.BUTTON_NEUTRAL}){
            Button b=dialog.getButton(which);if(b==null||b.getVisibility()!=View.VISIBLE)continue;
            styleActionButton(b,which==AlertDialog.BUTTON_POSITIVE);b.setTextSize(14);b.setPadding(dp(12),dp(6),dp(12),dp(6));
            b.setMinHeight(dp(48));b.setMinimumHeight(dp(48));
            android.view.ViewGroup.LayoutParams raw=b.getLayoutParams();raw.height=ViewGroup.LayoutParams.WRAP_CONTENT;
            if(raw instanceof android.view.ViewGroup.MarginLayoutParams){android.view.ViewGroup.MarginLayoutParams margins=(android.view.ViewGroup.MarginLayoutParams)raw;margins.setMarginStart(dp(5));margins.setMarginEnd(dp(5));}
            b.setLayoutParams(raw);
        }
    }

    private void marginAdd(LinearLayout parent,View child,int top,int bottom) { LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(top);p.bottomMargin=dp(bottom);parent.addView(child,p); }
    @Override public void onSaveInstanceState(Bundle state) {
        state.putInt("selected-page",currentPage);if(pendingRuleExport!=null)state.putString("pending-rule-export",pendingRuleExport);super.onSaveInstanceState(state);
    }
    @Override public void onBackPressed() {
        if(!appInitialized){finishAndRemoveTask();return;}
        if(drawerOpen){setDrawerOpen(false);return;}
        if(currentPage!=PAGE_HOME){showPage(PAGE_HOME);return;}
        super.onBackPressed();
    }
    @Override public void onStop() {
        if(!appInitialized){super.onStop();return;}
        if(outputSwitch!=null)outputSwitch.setChecked(false);
        stopVideo("离开前台，已停止");
        if(ble!=null) { ble.stopScan(); if(!"IDLE".equals(phase)&&!"DISCONNECTED".equals(phase)&&!"ERROR".equals(phase)) { log("应用离开前台，释放 M3 连接");ble.disconnect(); } }
        super.onStop();
    }
    @Override public void onDestroy() {
        destroyed=true;if(agreementDialog!=null){agreementDialog.dismiss();agreementDialog=null;}if(keysQuestionDialog!=null){keysQuestionDialog.dismiss();keysQuestionDialog=null;}
        palette.clear();
        if(appRoot!=null)appRoot.animate().cancel();
        if(drawerPanel!=null)drawerPanel.animate().cancel();if(drawerScrim!=null)drawerScrim.animate().cancel();
        for(ScrollView page:pages)if(page!=null)page.animate().cancel(); ++recognitionDisplayToken;recognitionExpiry.removeCallbacksAndMessages(null);if(modelEngine!=null)modelEngine.close();if(uvc!=null)uvc.close();if(ndi!=null)ndi.close();if(ble!=null)ble.close();if(usb!=null)usb.close();destroyed=true;super.onDestroy(); }
}
