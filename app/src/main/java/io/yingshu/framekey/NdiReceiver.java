/*
 * This project-authored Yingshu integration is licensed under AGPL-3.0.
 * The limited NDI linking permission is in LICENSE-NDI-EXCEPTION.txt.
 * It does not grant exceptions for upstream or other third-party materials.
 */
package io.yingshu.framekey;

import android.content.Context;
import android.graphics.Bitmap;
import android.net.nsd.NsdManager;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

/** Real NDI reception through the official SDK; it never generates a synthetic frame. */
public final class NdiReceiver {
    public interface Listener {
        void onSources(List<Source> sources);
        void onState(String phase, String detail);
        void onFrame(Frame frame);
        void onStats(String stats);
        void onLog(String message);
    }
    /** Caller owns both bitmaps after delivery; only recycle() an undisplayed/discarded frame. */
    public static final class Frame {
        public final Bitmap preview, panel;
        public final int sourceWidth, sourceHeight;
        /** Android elapsedRealtime, after SDK dequeue and before bitmap conversion. Not sender time. */
        public final long receivedAtMs;
        private Frame(Bitmap preview, Bitmap panel, int width, int height, long receivedAtMs) {
            this.preview=preview;this.panel=panel;sourceWidth=width;sourceHeight=height;
            this.receivedAtMs=receivedAtMs;
        }
        public void recycle() {
            if(preview!=null&&!preview.isRecycled())preview.recycle();
            if(panel!=null&&panel!=preview&&!panel.isRecycled())panel.recycle();
        }
    }
    private static final class PendingFrame {
        final int token;final Frame frame;
        PendingFrame(int token,Frame frame){this.token=token;this.frame=frame;}
    }
    private static final long MAX_UI_FRAME_AGE_MS=200;
    public static final class Source {
        public final String name;
        public final String urlAddress;
        public Source(String name, String urlAddress) {
            this.name = name == null ? "" : name;
            this.urlAddress = urlAddress == null ? "" : urlAddress;
        }
        @Override public String toString() { return name.isEmpty() ? urlAddress : name; }
    }

    private static final String LOAD_ERROR;
    static {
        String error = null;
        try { System.loadLibrary("ndi"); System.loadLibrary("yingshu_ndi"); }
        catch (Throwable t) { error = t.getClass().getSimpleName() + ": " + t.getMessage(); }
        LOAD_ERROR = error;
    }
    private final Handler main = new Handler(Looper.getMainLooper());
    private final HandlerThread thread = new HandlerThread("Yingshu-NDI");
    private final Handler worker;
    private final Listener listener;
    // The official Android SDK requires an existing NsdManager before NDI instances.
    @SuppressWarnings("unused") private final NsdManager nsdManager;
    private final WifiManager.MulticastLock multicastLock;
    private final Object frameLock = new Object();
    // One replaceable frame slot and at most one waiting main-thread delivery runnable.
    private PendingFrame pendingFrame;
    private boolean deliveryPosted;
    private final AtomicLong uiReplaced = new AtomicLong(), uiExpired = new AtomicLong();
    private final AtomicLong uiDelivered = new AtomicLong(), uiAgeTotal = new AtomicLong(), uiAgeMax = new AtomicLong();
    private final Runnable deliverLatest = this::deliverLatestFrame;
    private volatile int generation;
    private volatile boolean closed, previewEnabled = true, recognitionEnabled = true, eco, running;
    private volatile String state = "IDLE";
    // Native ownership belongs exclusively to worker.
    private long handle;
    private long statsStart, frameCount, previewCount, totalFrames, lastFrame, nextDelivery;
    private long diagnosticStart, totalDiscarded, conversionUs, conversions, sdkDropped, sdkReceived;
    private int queueDepth, queuePeak;
    private final int[] captureResult = new int[11];
    private final long[] captureTiming = new long[5];
    private int lastWidth, lastHeight;
    private String lastSources = "";

    public NdiReceiver(Context context, Listener listener) {
        Context app = context.getApplicationContext();
        this.listener = listener;
        nsdManager = (NsdManager) app.getSystemService(Context.NSD_SERVICE);
        WifiManager wifi = (WifiManager) app.getSystemService(Context.WIFI_SERVICE);
        multicastLock = wifi == null ? null : wifi.createMulticastLock("yingshu-ndi-discovery");
        if (multicastLock != null) multicastLock.setReferenceCounted(false);
        thread.start();
        worker = new Handler(thread.getLooper());
    }

    public String getState() { return state; }
    public boolean isRunning() { return running; }
    public void setPreviewEnabled(boolean enabled) { previewEnabled = enabled; }
    public void setEco(boolean enabled) { eco = enabled; }
    public void setRecognitionEnabled(boolean enabled) { recognitionEnabled = enabled; }
    public void discover() { discover(""); }

    public void discover(final String extraIps) {
        if (closed) return;
        final int token = ++generation;
        running = false;clearPendingFrame();
        worker.post(() -> {
            cleanup();
            if (!valid(token) || !initialize(token, extraIps)) return;
            lastSources = "";
            state(token, "DISCOVERING", "正在发现局域网 NDI 视频源");
            pollSources(token);
        });
    }

    public void connect(final Source source) {
        if (closed || source == null) return;
        final int token = ++generation;
        running = false;clearPendingFrame();
        worker.post(() -> {
            cleanup();
            if (!valid(token) || !initialize(token, "")) return;
            state(token, "CONNECTING", "正在连接 " + source.toString() + "，等待真实视频帧");
            try {
                if (!nativeConnect(handle, source.name, source.urlAddress)) {
                    fail(token, "NDI 接收器创建失败"); return;
                }
                statsStart = diagnosticStart = lastFrame = SystemClock.elapsedRealtime();
                frameCount = previewCount = totalFrames = 0; nextDelivery = 0; lastWidth = lastHeight = 0;
                totalDiscarded=conversionUs=conversions=sdkDropped=sdkReceived=0;queueDepth=queuePeak=0;
                uiReplaced.set(0);uiExpired.set(0);uiDelivered.set(0);uiAgeTotal.set(0);uiAgeMax.set(0);
                capture(token);
            } catch (Throwable t) { fail(token, readable(t)); }
        });
    }

    public void stop() {
        if (closed) return;
        final int token = ++generation;
        running = false;clearPendingFrame();
        worker.post(() -> { cleanup(); state(token, "STOPPED", "NDI 接收已停止"); });
    }

    public void close() {
        if (closed) return;
        closed = true; ++generation; running = false;clearPendingFrame();
        worker.post(() -> { cleanup(); thread.quitSafely(); });
    }

    private boolean initialize(int token, String extraIps) {
        if (LOAD_ERROR != null) { fail(token, "NDI 运行库无法加载：" + LOAD_ERROR); return false; }
        try {
            if (multicastLock != null && !multicastLock.isHeld()) multicastLock.acquire();
            handle = nativeCreate(extraIps == null ? "" : extraIps);
            if (handle == 0) { fail(token, "NDI 初始化或发现器创建失败"); return false; }
            log(token, "NDI 官方运行库：" + nativeVersion());
            return true;
        } catch (Throwable t) { fail(token, readable(t)); return false; }
    }

    private void pollSources(final int token) {
        if (!valid(token) || handle == 0) return;
        try {
            String[] values = nativeSources(handle);
            ArrayList<Source> sources = new ArrayList<>();
            StringBuilder signature = new StringBuilder();
            for (int i = 0; values != null && i + 1 < values.length; i += 2) {
                sources.add(new Source(values[i], values[i + 1]));
                signature.append(values[i]).append('\n').append(values[i + 1]).append('\n');
            }
            String current = signature.toString();
            if (!current.equals(lastSources) || current.isEmpty()) {
                lastSources = current;
                dispatch(token, () -> listener.onSources(sources));
                state(token, "DISCOVERING", sources.isEmpty() ? "暂未发现 NDI 源；请确认同一局域网及发送端输出" : "发现 " + sources.size() + " 个 NDI 源，请选择连接");
            }
            worker.postDelayed(() -> pollSources(token), 1500);
        } catch (Throwable t) { fail(token, readable(t)); }
    }

    private void capture(final int token) {
        if (!valid(token) || handle == 0) return;
        try {
            // The native call waits only when no source frame exists. It continuously
            // dequeues video even if display/inference is disabled or rate-limited.
            final Bitmap[] bitmaps = nativeCapture(handle, previewEnabled, recognitionEnabled,
                    nextDelivery, captureResult, captureTiming);
            final long now = SystemClock.elapsedRealtime();
            final Frame frame=bitmaps==null?null:new Frame(bitmaps[0],bitmaps[1],
                    captureResult[1],captureResult[2],captureTiming[0]);
            if (!valid(token)) { if(frame!=null)frame.recycle();return; }
            if (captureResult[0] == 1) {
                frameCount+=captureResult[6];totalFrames+=captureResult[6];lastFrame=now;
                totalDiscarded+=captureResult[7];queueDepth=captureResult[8];
                queuePeak=Math.max(queuePeak,captureResult[9]);
                sdkReceived=captureTiming[2];sdkDropped=captureTiming[3];
                lastWidth=captureResult[1];lastHeight=captureResult[2];
                if (!running || !"RUNNING".equals(state)) {
                    running = true;
                    state(token, "RUNNING", "已收到真实 NDI 视频帧：" + lastWidth + " × " + lastHeight);
                }
                if (captureResult[10]==0) {
                    if(frame!=null)frame.recycle();
                    fail(token,"NDI 视频颜色格式或步长不受支持");return;
                }
                if(frame!=null){
                    ++previewCount;++conversions;conversionUs+=captureTiming[1];
                    // One cadence, based on acquisition rather than conversion completion.
                    // Keep the phase when timely; skip missed slots rather than queueing them.
                    long interval=eco?100:33;
                    if(nextDelivery==0)nextDelivery=frame.receivedAtMs+interval;
                    else nextDelivery+=((Math.max(0,frame.receivedAtMs-nextDelivery)/interval)+1)*interval;
                    enqueueLatest(token,frame);
                }
            } else {
                if(frame!=null)frame.recycle();
                if(captureResult[0]==4){
                    running=false;
                    if(!"WAITING".equals(state))state(token,"WAITING","NDI 连接中断，等待发送端恢复");
                }else if(now-lastFrame>5000&&!"WAITING".equals(state)){
                    running=false;state(token,"WAITING","尚未收到视频帧；检查发送端格式、网络和防火墙");
                }
            }
            if(now-statsStart>=1000){
                double fps=frameCount*1000.0/(now-statsStart),displayFps=previewCount*1000.0/(now-statsStart);
                final String stats=String.format(Locale.US,
                    "%d × %d · 接收 %.1f fps · 投递 %.1f fps · 累计 %d 帧 · 链路 %d · 队列 %d · %s",
                    lastWidth,lastHeight,fps,displayFps,totalFrames,captureResult[5],queueDepth,
                    eco?"省电预览 10 fps":"低延迟预览");
                dispatch(token,()->listener.onStats(stats));statsStart=now;frameCount=previewCount=0;
            }
            if(now-diagnosticStart>=5000){
                long delivered=uiDelivered.get(),ageSum=uiAgeTotal.get();
                log(token,String.format(Locale.US,
                    "NDI_LATENCY sdkReceived=%d sdkDropped=%d queue=%d peak=%d discarded=%d uiReplaced=%d uiExpired=%d delivered=%d copyAvgMs=%.3f uiAgeAvgMs=%.1f uiAgeMaxMs=%d (dequeue-to-delivery, not end-to-end)",
                    sdkReceived,sdkDropped,queueDepth,queuePeak,totalDiscarded,uiReplaced.get(),uiExpired.get(),
                    delivered,conversions==0?0:conversionUs/1000.0/conversions,
                    delivered==0?0:ageSum*1.0/delivered,uiAgeMax.get()));
                diagnosticStart=now;
            }
            // Never sleep for eco here: receiving/draining is independent of presentation rate.
            worker.post(()->capture(token));
        }catch(Throwable t){fail(token,readable(t));}
    }

    private void enqueueLatest(int token,Frame frame){
        PendingFrame displaced=null;
        synchronized(frameLock){
            if(!valid(token)){frame.recycle();return;}
            displaced=pendingFrame;pendingFrame=new PendingFrame(token,frame);
            if(!deliveryPosted){deliveryPosted=true;main.post(deliverLatest);}
        }
        if(displaced!=null){uiReplaced.incrementAndGet();displaced.frame.recycle();}
    }

    private void deliverLatestFrame(){
        final PendingFrame pending;
        synchronized(frameLock){pending=pendingFrame;pendingFrame=null;deliveryPosted=false;}
        if(pending==null)return;
        long age=SystemClock.elapsedRealtime()-pending.frame.receivedAtMs;
        if(!valid(pending.token)||age<0||age>MAX_UI_FRAME_AGE_MS){
            uiExpired.incrementAndGet();pending.frame.recycle();return;
        }
        uiDelivered.incrementAndGet();uiAgeTotal.addAndGet(age);
        long old=uiAgeMax.get();while(age>old&&!uiAgeMax.compareAndSet(old,age))old=uiAgeMax.get();
        listener.onFrame(pending.frame);
    }

    private void clearPendingFrame(){
        PendingFrame pending;
        synchronized(frameLock){pending=pendingFrame;pendingFrame=null;}
        // Keep deliveryPosted until its queued runnable executes, so reconnecting cannot
        // queue a second delivery runnable while the old one still owns the slot.
        if(pending!=null)pending.frame.recycle();
    }

    private boolean valid(int token) { return !closed && token == generation; }
    private void dispatch(int token, Runnable callback) { main.post(() -> { if (valid(token)) callback.run(); }); }
    private void state(int token, String phase, String detail) {
        if (!valid(token)) return;
        state = phase;
        if(!"RUNNING".equals(phase))clearPendingFrame();
        dispatch(token, () -> listener.onState(phase, detail));
    }
    private void log(int token, String message) { dispatch(token, () -> listener.onLog(message)); }
    private void fail(int token, String detail) {
        cleanup(); running = false;
        state(token, "ERROR", detail);
        log(token, "NDI 错误：" + detail);
    }
    private void cleanup() {
        clearPendingFrame();
        if (handle != 0) { nativeDestroy(handle); handle = 0; }
        if (multicastLock != null && multicastLock.isHeld()) multicastLock.release();
    }
    private static String readable(Throwable t) { return t.getClass().getSimpleName() + ": " + t.getMessage(); }

    private static native String nativeVersion();
    private static native long nativeCreate(String extraIps);
    private static native String[] nativeSources(long handle);
    private static native boolean nativeConnect(long handle, String name, String address);
    private static native Bitmap[] nativeCapture(long handle, boolean wantPreview, boolean wantPanel,
            long deliveryNotBeforeMs, int[] result, long[] timing);
    private static native void nativeDestroy(long handle);
}
