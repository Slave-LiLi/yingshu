package io.yingshu.framekey;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * External UVC MJPEG / BULK receiver. Does not use Android camera APIs.
 * USB-IF UVC probe/commit and payload framing are independently implemented.
 */
public final class UvcCapture {
    public interface Listener {
        void onState(String phase, String detail);
        /** Ownership transfers to the UI. This class will never recycle a delivered bitmap. */
        void onFrame(Bitmap bitmap);
        void onStats(String stats);
        void onLog(String line);
    }

    private final UsbManager manager;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicLong generation = new AtomicLong();
    private final ExecutorService input = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "Yingshu-UVC-USB");
        thread.setDaemon(true);
        return thread;
    });
    private volatile boolean previewEnabled = true;
    private volatile boolean closed;
    private volatile Session current;

    private static final class Session {
        final long generation;
        final int deviceId;
        final boolean eco;
        final Object decoderLock = new Object();
        final Object previewLock = new Object();
        volatile boolean active = true;
        volatile UsbDeviceConnection connection;
        volatile Thread decoder;
        volatile UvcDescriptors.Mode mode;
        volatile long decodedFrames, decodeFailures, replacedJpegs, lastDecodedAtMs;
        volatile int width, height;
        volatile boolean firstFrame;
        volatile String decodeFatalError;
        long capturedFrames, offeredAtNs, receivedBytes;
        long startedAtMs, statsAtMs, statsCaptured, statsDecoded;
        byte[] latestJpeg;
        Bitmap pendingBitmap;
        boolean previewPosted;

        Session(long generation, int deviceId, boolean eco) {
            this.generation = generation;
            this.deviceId = deviceId;
            this.eco = eco;
        }
    }

    private static final class Negotiated {
        final UvcDescriptors.Mode mode;
        final long interval;
        final int maxPayloadBytes, maxFrameBytes;
        Negotiated(UvcDescriptors.Mode mode, long interval, int payload, int frame) {
            this.mode = mode; this.interval = interval; maxPayloadBytes = payload; maxFrameBytes = frame;
        }
    }

    public UvcCapture(Context context, Listener listener) {
        this.listener = listener;
        manager = (UsbManager) context.getApplicationContext().getSystemService(Context.USB_SERVICE);
    }

    public synchronized void start(int deviceId, boolean eco) {
        if (closed) return;
        cancel(current);
        Session session = new Session(generation.incrementAndGet(), deviceId, eco);
        current = session;
        state(session, "STARTING", "正在打开外接采集卡并协商 1920×1080 MJPEG 视频流…");
        input.execute(() -> run(session));
    }

    public synchronized void stop() {
        if (closed) return;
        Session old = current;
        current = null;
        cancel(old);
        long token = generation.incrementAndGet();
        main.post(() -> {
            if (!closed && generation.get() == token) listener.onState("STOPPED", "视频采集已停止。");
        });
    }

    public synchronized void close() {
        if (closed) return;
        closed = true;
        generation.incrementAndGet();
        cancel(current);
        current = null;
        input.shutdownNow();
    }

    public void setPreviewEnabled(boolean enabled) {
        previewEnabled = enabled;
        // Frame delivery also feeds inference; hiding the preview must not stop it.
    }

    /** Processing cap changes with a clean stream restart so the device can renegotiate too. */
    public synchronized void setEco(boolean eco) {
        Session session = current;
        if (!closed && session != null && session.active && session.eco != eco) {
            start(session.deviceId, eco);
            log(current, "节能模式已改变，正在重新协商设备帧率；处理上限为 " + (eco ? 10 : 30) + " fps。");
        }
    }

    public boolean isRunning() {
        Session session = current;
        return !closed && session != null && session.active;
    }

    private void run(Session session) {
        UsbDeviceConnection connection = null;
        UsbInterface streaming = null;
        boolean claimed = false;
        String failure = null;
        String failurePhase = "ERROR";
        try {
            checkActive(session);
            UsbDevice device = findDevice(session.deviceId);
            if (device == null) throw new CaptureFailure("采集卡未连接，请刷新 USB 列表后重试。");
            if (!manager.hasPermission(device)) {
                failurePhase = "PERMISSION_REQUIRED";
                throw new CaptureFailure("尚未取得外接采集卡权限，请先完成 USB 授权。");
            }
            connection = manager.openDevice(device);
            if (connection == null) throw new CaptureFailure("无法打开采集卡，可能被其他应用占用或供电不足。");
            session.connection = connection;
            checkActive(session);
            UvcDescriptors descriptors = UvcDescriptors.parse(connection.getRawDescriptors());
            if (descriptors.malformed) log(session, "部分 UVC 描述符不完整，仅使用已通过长度校验的模式。");
            UsbEndpoint endpoint = null;
            for (int i = 0; i < device.getInterfaceCount() && endpoint == null; i++) {
                UsbInterface candidate = device.getInterface(i);
                if (candidate.getInterfaceClass() != 14 || candidate.getInterfaceSubclass() != 2) continue;
                if (descriptors.preferredModes(candidate.getId()).isEmpty()) continue;
                for (int e = 0; e < candidate.getEndpointCount(); e++) {
                    UsbEndpoint ep = candidate.getEndpoint(e);
                    if (ep.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK && ep.getDirection() == UsbConstants.USB_DIR_IN) {
                        streaming = candidate; endpoint = ep; break;
                    }
                }
            }
            if (streaming == null || endpoint == null) {
                throw new CaptureFailure("采集卡未提供可用的 1920×1080 MJPEG / BULK IN 模式，无法开始 1080p 采集。");
            }
            claimed = connection.claimInterface(streaming, true);
            if (!claimed) throw new CaptureFailure("无法占用 UVC 视频流接口，请关闭其他采集应用后重试。");
            checkActive(session);
            if (!connection.setInterface(streaming) && streaming.getAlternateSetting() != 0) {
                throw new CaptureFailure("无法切换采集卡的视频接口设置。");
            }
            log(session, String.format(Locale.ROOT, "UVC 接口 %d alt %d / BULK IN EP %02X / maxPacket %d / version %04X",
                    streaming.getId(), streaming.getAlternateSetting(), endpoint.getAddress(), endpoint.getMaxPacketSize(), descriptors.version));
            List<UvcDescriptors.Mode> candidates = descriptors.preferredModes(streaming.getId());
            Negotiated negotiated = null;
            String lastNegotiationError = "没有可用模式";
            // Only full-HD candidates enter negotiation, including in eco mode.
            for (int i = 0; i < Math.min(candidates.size(), 4); i++) {
                checkActive(session);
                try {
                    negotiated = negotiate(session, connection, descriptors, candidates.get(i));
                    break;
                } catch (CaptureFailure error) {
                    lastNegotiationError = error.getMessage();
                    log(session, "UVC 协商未通过：" + candidates.get(i) + " · " + lastNegotiationError);
                }
            }
            if (negotiated == null) throw new CaptureFailure("1920×1080 视频参数协商失败：" + lastNegotiationError);
            session.mode = negotiated.mode;
            session.width = negotiated.mode.width;
            session.height = negotiated.mode.height;
            log(session, String.format(Locale.ROOT, "UVC 已提交 %s，设备协商 %.2f fps，payload=%d B，frameLimit=%d B，处理上限=%d fps",
                    negotiated.mode, 10_000_000.0 / negotiated.interval, negotiated.maxPayloadBytes,
                    negotiated.maxFrameBytes, session.eco ? 10 : 30));
            state(session, "STARTING", "视频参数已提交，正在等待首张可解码画面…");
            session.startedAtMs = session.statsAtMs = SystemClock.elapsedRealtime();
            Thread decoder = new Thread(() -> decodeLoop(session), "Yingshu-UVC-Decode");
            decoder.setDaemon(true);
            session.decoder = decoder;
            decoder.start();
            UvcPayloadAssembler assembler = new UvcPayloadAssembler(negotiated.maxPayloadBytes, negotiated.maxFrameBytes,
                    jpeg -> offerJpeg(session, jpeg));
            int requestLength = Math.min(16 * 1024, negotiated.maxPayloadBytes);
            byte[] transfer = new byte[requestLength];
            long lastBytesAtMs = session.startedAtMs;
            boolean firstTransferLogged = false;
            while (isCurrent(session)) {
                if (session.decodeFatalError != null) throw new CaptureFailure(session.decodeFatalError);
                int received = connection.bulkTransfer(endpoint, transfer, requestLength, 500);
                if (!isCurrent(session)) break;
                if (session.decodeFatalError != null) throw new CaptureFailure(session.decodeFatalError);
                long now = SystemClock.elapsedRealtime();
                if (received >= 0) {
                    if (received > 0) {
                        session.receivedBytes += received;
                        lastBytesAtMs = now;
                        if (!firstTransferLogged) {
                            log(session, "UVC 首次 BULK 返回 " + received + " B，前缀 " + hex(transfer, Math.min(received, 16)));
                            firstTransferLogged = true;
                        }
                    }
                    assembler.accept(transfer, received, requestLength);
                } else {
                    assembler.discontinuity();
                    // Disconnected devices can fail immediately rather than waiting 500 ms.
                    SystemClock.sleep(15);
                }
                if (now - lastBytesAtMs > 5000) throw new CaptureFailure("采集卡连续 5 秒未返回视频数据，请检查 HDMI 信号、USB 连接和设备占用。");
                if (!session.firstFrame && now - session.startedAtMs > 9000) {
                    throw new CaptureFailure("已接收 " + session.receivedBytes + " 字节，但 9 秒内没有可解码画面。"
                            + "完整帧=" + assembler.frames + "，无效payload=" + assembler.invalidPayloads
                            + "，丢弃帧=" + assembler.droppedFrames + "，解码失败=" + session.decodeFailures);
                }
                if (session.firstFrame && now - session.lastDecodedAtMs > 9000) {
                    throw new CaptureFailure("采集卡已有 9 秒没有新的可解码画面，请检查 HDMI 信号与 USB 连接。");
                }
                if (now - session.statsAtMs >= 1000) publishStats(session, assembler, now);
            }
        } catch (CaptureCancelled ignored) {
            // The next generation owns the UI; no stale state is emitted.
        } catch (SecurityException error) {
            failurePhase = "PERMISSION_REQUIRED";
            failure = "系统拒绝访问外接采集卡，请检查 USB 与外接视频设备权限。";
            log(session, error.toString());
        } catch (Exception error) {
            failure = error.getMessage() == null ? error.toString() : error.getMessage();
            log(session, "UVC 错误：" + error);
        } finally {
            session.active = false;
            synchronized (session.decoderLock) {
                session.latestJpeg = null;
                session.decoderLock.notifyAll();
            }
            if (session.decoder != null) session.decoder.interrupt();
            synchronized (session.previewLock) {
                recycle(session.pendingBitmap);
                session.pendingBitmap = null;
            }
            if (connection != null) {
                if (claimed && streaming != null) {
                    try { connection.releaseInterface(streaming); } catch (RuntimeException ignored) { }
                }
                try { connection.close(); } catch (RuntimeException ignored) { }
            }
            session.connection = null;
            synchronized (this) { if (current == session) current = null; }
            if (failure != null) state(session, failurePhase, failure);
        }
    }

    private Negotiated negotiate(Session session, UsbDeviceConnection connection,
            UvcDescriptors descriptors, UvcDescriptors.Mode wanted) throws Exception {
        int size = descriptors.version >= 0x0150 ? 48 : (descriptors.version >= 0x0110 ? 34 : 26);
        byte[] len = new byte[2];
        int read = control(session, connection, 0xa1, 0x85, 0x0100, wanted.interfaceId, len);
        if (read == 2) {
            int reported = UvcDescriptors.u16(len, 0);
            if (reported == 26 || reported == 34 || reported == 48) size = reported;
        }
        byte[] probe = new byte[size];
        int baseline = control(session, connection, 0xa1, 0x81, 0x0100, wanted.interfaceId, probe);
        if (baseline < 26) Arrays.fill(probe, (byte) 0);
        probe[0] = 1; probe[1] = 0; // bmHint: frame interval is specified by host.
        probe[2] = (byte) wanted.formatIndex;
        probe[3] = (byte) wanted.frameIndex;
        UvcDescriptors.put32(probe, 4, wanted.chooseInterval(session.eco));
        long advertisedFrame = wanted.maxFrameBytes > 0 ? wanted.maxFrameBytes : (long) wanted.width * wanted.height * 2;
        UvcDescriptors.put32(probe, 18, Math.min(8 * 1024 * 1024L, advertisedFrame));
        int written = control(session, connection, 0x21, 0x01, 0x0100, wanted.interfaceId, probe);
        if (written != size) throw new CaptureFailure("SET_CUR(PROBE) 返回 " + written + "，预期 " + size);
        byte[] agreed = new byte[size];
        int returned = control(session, connection, 0xa1, 0x81, 0x0100, wanted.interfaceId, agreed);
        if (returned < 26) throw new CaptureFailure("GET_CUR(PROBE) 返回不足：" + returned);
        if (returned != size) {
            if (returned != 26 && returned != 34 && returned != 48) throw new CaptureFailure("不支持的 PROBE 长度：" + returned);
            agreed = Arrays.copyOf(agreed, returned);
        }
        int format = agreed[2] & 255, frame = agreed[3] & 255;
        UvcDescriptors.Mode chosen = descriptors.find(wanted.interfaceId, format, frame);
        if (chosen == null) throw new CaptureFailure("设备返回未声明的 MJPEG 模式 format=" + format + " frame=" + frame);
        if (!UvcDescriptors.isFullHd(chosen.width, chosen.height)) {
            throw new CaptureFailure("要求 1920×1080，但设备实际返回 " + chosen.width + "×" + chosen.height
                    + "（format=" + format + " frame=" + frame + "），已中止此次协商。");
        }
        long interval = UvcDescriptors.u32(agreed, 4);
        long payload = UvcDescriptors.u32(agreed, 22);
        long frameLimit = UvcDescriptors.u32(agreed, 18);
        if (interval <= 0 || interval > 100_000_000L) throw new CaptureFailure("设备返回无效帧间隔：" + interval);
        if (payload < 2 || payload > 16 * 1024 * 1024L) throw new CaptureFailure("设备返回无效 payload 上限：" + payload);
        if (frameLimit < 4) frameLimit = Math.max(chosen.maxFrameBytes, (long) chosen.width * chosen.height * 2);
        frameLimit = Math.max(256 * 1024L, Math.min(8 * 1024 * 1024L, frameLimit));
        int committed = control(session, connection, 0x21, 0x01, 0x0200, wanted.interfaceId, agreed);
        if (committed != agreed.length) throw new CaptureFailure("SET_CUR(COMMIT) 返回 " + committed + "，预期 " + agreed.length);
        return new Negotiated(chosen, interval, (int) payload, (int) frameLimit);
    }

    private int control(Session session, UsbDeviceConnection connection, int type, int request,
            int value, int intf, byte[] bytes) throws CaptureCancelled {
        checkActive(session);
        int result = connection.controlTransfer(type, request, value, intf, bytes, bytes.length, 700);
        checkActive(session);
        return result;
    }

    private void offerJpeg(Session session, byte[] jpeg) {
        session.capturedFrames++;
        long now = System.nanoTime();
        long interval = session.eco ? 100_000_000L : 33_333_333L;
        if (!isCurrent(session) || now - session.offeredAtNs < interval) return;
        session.offeredAtNs = now;
        synchronized (session.decoderLock) {
            if (session.latestJpeg != null) session.replacedJpegs++;
            session.latestJpeg = jpeg;
            session.decoderLock.notifyAll();
        }
    }

    private void decodeLoop(Session session) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        while (isCurrent(session)) {
            byte[] jpeg;
            synchronized (session.decoderLock) {
                while (session.latestJpeg == null && isCurrent(session)) {
                    try { session.decoderLock.wait(500); }
                    catch (InterruptedException ignored) { return; }
                }
                if (!isCurrent(session)) return;
                jpeg = session.latestJpeg;
                session.latestJpeg = null;
            }
            Bitmap bitmap = null;
            try {
                // Bounds decode first prevents malformed MJPEG dimensions allocating unbounded memory.
                options.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, options);
                if (options.outWidth <= 0 || options.outHeight <= 0
                        || (long) options.outWidth * options.outHeight > 8_294_400L) {
                    session.decodeFailures++;
                    continue;
                }
                if (!UvcDescriptors.isFullHd(options.outWidth, options.outHeight)) {
                    session.decodeFatalError = "要求 1920×1080，但采集卡实际输出的 MJPEG 画面为 "
                            + options.outWidth + "×" + options.outHeight + "，已停止采集。";
                    return;
                }
                options.inJustDecodeBounds = false;
                bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, options);
                if (bitmap == null) {
                    session.decodeFailures++;
                    continue;
                }
                if (!isCurrent(session)) { recycle(bitmap); return; }
                session.width = bitmap.getWidth();
                session.height = bitmap.getHeight();
                session.decodedFrames++;
                session.lastDecodedAtMs = SystemClock.elapsedRealtime();
                if (!session.firstFrame) {
                    session.firstFrame = true;
                    state(session, "RUNNING", "已收到并解码电脑画面 · " + session.width + "×" + session.height + " · MJPEG");
                }
                publishBitmap(session, bitmap);
                bitmap = null;
            } catch (OutOfMemoryError error) {
                recycle(bitmap);
                session.decodeFailures++;
                log(session, "视频帧内存不足，已丢弃当前帧。");
            } catch (RuntimeException error) {
                recycle(bitmap);
                session.decodeFailures++;
                if (session.decodeFailures <= 3) log(session, "MJPEG 解码失败：" + error);
            }
        }
    }

    private void publishBitmap(Session session, Bitmap bitmap) {
        synchronized (session.previewLock) {
            if (!isCurrent(session)) { recycle(bitmap); return; }
            recycle(session.pendingBitmap);
            session.pendingBitmap = bitmap;
            if (session.previewPosted) return;
            session.previewPosted = true;
        }
        main.post(() -> {
            Bitmap delivered;
            synchronized (session.previewLock) {
                delivered = session.pendingBitmap;
                session.pendingBitmap = null;
                session.previewPosted = false;
            }
            if (delivered == null) return;
            if (isCurrent(session)) listener.onFrame(delivered);
            else recycle(delivered);
        });
    }

    private void publishStats(Session session, UvcPayloadAssembler assembler, long now) {
        double seconds = (now - session.statsAtMs) / 1000.0;
        double capturedFps = (session.capturedFrames - session.statsCaptured) / seconds;
        double decodedFps = (session.decodedFrames - session.statsDecoded) / seconds;
        session.statsAtMs = now;
        session.statsCaptured = session.capturedFrames;
        session.statsDecoded = session.decodedFrames;
        String text = String.format(Locale.ROOT,
                "%d×%d · 接收 %.1f fps · 处理 %.1f fps\n累计 %d 帧 · %.1f MB · 丢弃 %d · 解码失败 %d%s%s",
                session.width, session.height, capturedFps, decodedFps, session.capturedFrames,
                session.receivedBytes / 1048576.0, assembler.droppedFrames, session.decodeFailures,
                session.eco ? " · 节能上限10fps" : "", previewEnabled ? "" : " · 预览已关闭");
        main.post(() -> { if (isCurrent(session)) listener.onStats(text); });
    }

    private UsbDevice findDevice(int id) {
        if (manager == null) return null;
        for (UsbDevice device : manager.getDeviceList().values()) {
            if (device.getDeviceId() == id) return device;
        }
        return null;
    }

    private void cancel(Session session) {
        if (session == null) return;
        session.active = false;
        synchronized (session.decoderLock) {
            session.latestJpeg = null;
            session.decoderLock.notifyAll();
        }
        if (session.decoder != null) session.decoder.interrupt();
        synchronized (session.previewLock) {
            recycle(session.pendingBitmap);
            session.pendingBitmap = null;
        }
        // USB I/O has finite 500/700 ms timeouts. Its worker releases its own
        // interface and connection, before the serialized next start can run.
    }

    private boolean isCurrent(Session session) {
        return !closed && session != null && session.active && current == session && generation.get() == session.generation;
    }

    private void checkActive(Session session) throws CaptureCancelled {
        if (!isCurrent(session)) throw new CaptureCancelled();
    }

    private void state(Session session, String phase, String detail) {
        main.post(() -> {
            if (!closed && generation.get() == session.generation) listener.onState(phase, detail);
        });
    }

    private void log(Session session, String message) {
        if (session == null) return;
        main.post(() -> {
            if (!closed && generation.get() == session.generation) listener.onLog(message);
        });
    }

    private static String hex(byte[] bytes, int length) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < length; i++) {
            if (i != 0) result.append(' ');
            result.append(String.format(Locale.ROOT, "%02X", bytes[i] & 255));
        }
        return result.toString();
    }

    private static void recycle(Bitmap bitmap) {
        if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
    }

    private static final class CaptureCancelled extends Exception { }
    private static final class CaptureFailure extends Exception {
        CaptureFailure(String message) { super(message); }
    }
}
