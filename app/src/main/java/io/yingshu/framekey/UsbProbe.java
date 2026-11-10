package io.yingshu.framekey;

import android.Manifest;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Read-only external USB video-device diagnostics. Never starts a video stream. */
public final class UsbProbe {
    public interface Listener {
        void onDevices(List<DeviceInfo> devices);
        void onLog(String line);
        void onResult(String status, String detail);
    }

    public static final class DeviceInfo {
        public final int id;
        public final String name;
        public final int vendorId;
        public final int productId;
        public final boolean uvc;
        public final boolean hasPermission;
        public final String description;

        DeviceInfo(UsbDevice device, boolean permission) {
            id = device.getDeviceId();
            name = deviceName(device);
            vendorId = device.getVendorId();
            productId = device.getProductId();
            uvc = isUvc(device);
            hasPermission = permission;
            description = describeDevice(device);
        }
    }

    private final Context context;
    private final Listener listener;
    private final UsbManager manager;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Yingshu-UsbProbe");
        t.setDaemon(true);
        return t;
    });
    private final String permissionAction;
    private boolean registered;
    private volatile boolean closed;
    private int generation;
    private int pendingDeviceId = -1;
    private String pendingToken;
    private PendingIntent pendingIntent;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context ignored, Intent intent) {
            if (closed || intent == null) return;
            String action = intent.getAction();
            if (permissionAction.equals(action)) {
                UsbDevice device = getDeviceExtra(intent);
                if (device == null || device.getDeviceId() != pendingDeviceId
                        || pendingToken == null
                        || !pendingToken.equals(intent.getStringExtra("request_token"))) {
                    log("忽略过期或不匹配的 USB 授权结果");
                    return;
                }
                int ticket = generation;
                clearPendingPermission();
                boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
                UsbDevice current = findDevice(device.getDeviceId());
                if (granted && current != null && hasPermission(current)) {
                    log("USB 权限已获准：" + deviceName(current));
                    publishDevices();
                    probe(current, ticket);
                } else {
                    result("PERMISSION_DENIED", "未取得外接 USB 设备权限，尚未打开设备。");
                    publishDevices();
                }
            } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
                log("检测到 USB 设备接入");
                refresh();
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                UsbDevice removed = getDeviceExtra(intent);
                generation++;
                clearPendingPermission();
                log("USB 设备已拔出" + (removed == null ? "" : "：" + deviceName(removed)));
                refresh();
            }
        }
    };

    public UsbProbe(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        manager = (UsbManager) this.context.getSystemService(Context.USB_SERVICE);
        permissionAction = this.context.getPackageName() + ".USB_PROBE_PERMISSION";
        IntentFilter filter = new IntentFilter(permissionAction);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        if (Build.VERSION.SDK_INT >= 33) {
            this.context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            this.context.registerReceiver(receiver, filter);
        }
        registered = true;
    }

    public void refresh() {
        onMain(() -> {
            if (closed) return;
            if (manager == null || !context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_USB_HOST)) {
                listener.onDevices(Collections.emptyList());
                result("UNSUPPORTED", "系统未提供 USB Host 能力，无法枚举外接采集设备。");
                return;
            }
            List<DeviceInfo> devices = publishDevices();
            int count = 0;
            for (DeviceInfo info : devices) if (info.uvc) count++;
            if (count == 0) {
                result("NO_DEVICE", devices.isEmpty()
                        ? "未发现外接 USB 设备。请通过 OTG 连接采集卡；当前未验证视频输入。"
                        : "已发现 " + devices.size() + " 个 USB 设备，但没有视频类接口（UVC）。");
            } else {
                result("FOUND", "发现 " + count + " 个外接 UVC 候选设备；点击设备验证授权与打开。");
            }
        });
    }

    public void requestOpen(final int deviceId) {
        onMain(() -> {
            if (closed) return;
            generation++;
            clearPendingPermission();
            UsbDevice device = findDevice(deviceId);
            if (device == null) {
                result("NO_DEVICE", "设备已离线，请刷新后重新选择。");
                publishDevices();
                return;
            }
            if (!isUvc(device)) {
                result("NOT_UVC", "所选设备没有 UVC 视频类接口；本版仅测试外接视频采集设备。");
                return;
            }
            // Android gates USB video-device permission behind CAMERA on API 28+.
            // The activity owns this permission prompt; this module has no camera API calls.
            if (Build.VERSION.SDK_INT >= 28
                    && context.getApplicationInfo().targetSdkVersion >= 28
                    && context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                result("CAMERA_PERMISSION_REQUIRED", "Android 要求先授予摄像头权限才能授权外接 UVC。此权限仅用于 USB 采集卡；不会调用前后置摄像头。");
                return;
            }
            if (hasPermission(device)) {
                probe(device, generation);
                return;
            }
            pendingDeviceId = deviceId;
            pendingToken = UUID.randomUUID().toString();
            Intent callback = new Intent(permissionAction).setPackage(context.getPackageName());
            callback.putExtra("request_token", pendingToken);
            int flags = PendingIntent.FLAG_CANCEL_CURRENT;
            // USB service supplies EXTRA_DEVICE and EXTRA_PERMISSION_GRANTED via fill-in.
            if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
            pendingIntent = PendingIntent.getBroadcast(context, deviceId, callback, flags);
            try {
                result("PERMISSION_REQUIRED", "等待系统 USB 授权：" + deviceName(device));
                manager.requestPermission(device, pendingIntent);
            } catch (RuntimeException error) {
                clearPendingPermission();
                result("ERROR", "USB 授权请求失败：" + message(error));
            }
        });
    }

    private void probe(final UsbDevice device, final int ticket) {
        result("OPENING", "已授权，正在打开外接设备并读取描述符。");
        io.execute(() -> {
            UsbDeviceConnection connection = null;
            String status;
            String detail;
            try {
                if (closed) return;
                connection = manager.openDevice(device);
                if (connection == null) {
                    status = "ERROR";
                    detail = "USB 权限存在，但 openDevice 返回空；请检查设备占用、OTG 供电或重新插拔。";
                } else {
                    byte[] descriptors = connection.getRawDescriptors();
                    String formats = describeVideoDescriptors(descriptors);
                    status = "OPENED";
                    detail = "外接设备可打开，测试句柄已关闭。\n"
                            + describeDevice(device) + "\n"
                            + "描述符：" + (descriptors == null ? 0 : descriptors.length) + " 字节\n"
                            + formats + "\n"
                            + "本轮仅验证 USB 授权与设备访问，尚未开始视频流或验证画面。";
                }
            } catch (SecurityException error) {
                status = "PERMISSION_DENIED";
                detail = "系统拒绝打开 USB 设备：" + message(error);
            } catch (RuntimeException error) {
                status = "ERROR";
                detail = "USB 打开／描述符读取失败：" + message(error);
            } finally {
                if (connection != null) connection.close();
            }
            final String deliveredStatus = status;
            final String deliveredDetail = detail;
            main.post(() -> {
                if (closed || ticket != generation) return;
                result(deliveredStatus, deliveredDetail);
                publishDevices();
            });
        });
    }

    private List<DeviceInfo> publishDevices() {
        List<DeviceInfo> devices = new ArrayList<>();
        if (manager != null) {
            try {
                for (UsbDevice device : manager.getDeviceList().values()) {
                    devices.add(new DeviceInfo(device, hasPermission(device)));
                }
            } catch (RuntimeException error) {
                log("USB 枚举失败：" + message(error));
            }
        }
        Collections.sort(devices, Comparator.comparing((DeviceInfo d) -> !d.uvc).thenComparing(d -> d.name));
        listener.onDevices(Collections.unmodifiableList(devices));
        return devices;
    }

    private UsbDevice findDevice(int id) {
        if (manager == null) return null;
        try {
            for (UsbDevice device : manager.getDeviceList().values()) {
                if (device.getDeviceId() == id) return device;
            }
        } catch (RuntimeException error) {
            log("读取 USB 设备列表失败：" + message(error));
        }
        return null;
    }

    private boolean hasPermission(UsbDevice device) {
        try { return manager != null && manager.hasPermission(device); }
        catch (RuntimeException ignored) { return false; }
    }

    private static boolean isUvc(UsbDevice device) {
        if (device.getDeviceClass() == UsbConstants.USB_CLASS_VIDEO) return true;
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            if (device.getInterface(i).getInterfaceClass() == UsbConstants.USB_CLASS_VIDEO) return true;
        }
        return false;
    }

    private static String deviceName(UsbDevice device) {
        try {
            String product = device.getProductName();
            if (product != null && !product.trim().isEmpty()) return product;
        } catch (RuntimeException ignored) { }
        return String.format(Locale.ROOT, "USB %04X:%04X", device.getVendorId(), device.getProductId());
    }

    private static String describeDevice(UsbDevice device) {
        StringBuilder out = new StringBuilder();
        out.append(String.format(Locale.ROOT, "VID:PID %04X:%04X · %d 个接口",
                device.getVendorId(), device.getProductId(), device.getInterfaceCount()));
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface inf = device.getInterface(i);
            out.append("\n接口 ").append(inf.getId()).append(" / alt ").append(inf.getAlternateSetting())
                    .append(" · class ").append(inf.getInterfaceClass())
                    .append(" / sub ").append(inf.getInterfaceSubclass());
            if (inf.getInterfaceClass() == UsbConstants.USB_CLASS_VIDEO) {
                if (inf.getInterfaceSubclass() == 1) out.append(" (视频控制)");
                else if (inf.getInterfaceSubclass() == 2) out.append(" (视频流)");
            }
            for (int n = 0; n < inf.getEndpointCount(); n++) {
                UsbEndpoint ep = inf.getEndpoint(n);
                out.append(String.format(Locale.ROOT, "\n  EP %02X · %s · %s · max %d B",
                        ep.getAddress(), ep.getDirection() == UsbConstants.USB_DIR_IN ? "IN" : "OUT",
                        endpointType(ep.getType()), ep.getMaxPacketSize()));
            }
        }
        return out.toString();
    }

    private static String endpointType(int type) {
        switch (type) {
            case UsbConstants.USB_ENDPOINT_XFER_ISOC: return "ISO";
            case UsbConstants.USB_ENDPOINT_XFER_BULK: return "BULK";
            case UsbConstants.USB_ENDPOINT_XFER_INT: return "INT";
            default: return "CONTROL";
        }
    }

    /** Parses only standard UVC descriptor layout; malformed lengths stop parsing. */
    static String describeVideoDescriptors(byte[] data) {
        if (data == null || data.length < 2) return "设备未返回可解析的描述符。";
        Set<String> formats = new LinkedHashSet<>();
        Set<String> frames = new LinkedHashSet<>();
        boolean videoStreaming = false;
        String currentFormat = "UVC";
        boolean truncated = false;
        int p = 0;
        while (p + 2 <= data.length) {
            int length = data[p] & 255;
            int type = data[p + 1] & 255;
            if (length < 2 || p + length > data.length) { truncated = true; break; }
            if (type == 4) {
                videoStreaming = length >= 9 && (data[p + 5] & 255) == 14 && (data[p + 6] & 255) == 2;
                currentFormat = "UVC";
            } else if (videoStreaming && type == 0x24 && length >= 3) {
                int subtype = data[p + 2] & 255;
                if ((subtype == 0x04 || subtype == 0x10) && length >= 21) {
                    currentFormat = fourcc(data, p + 5);
                    formats.add(currentFormat + (subtype == 0x10 ? " (frame-based)" : ""));
                } else if (subtype == 0x06 && length >= 11) {
                    currentFormat = "MJPEG";
                    formats.add(currentFormat);
                } else if (subtype == 0x13 || subtype == 0x15) {
                    currentFormat = "H.264";
                    formats.add(currentFormat);
                } else if (subtype == 0x16) {
                    currentFormat = "VP8";
                    formats.add(currentFormat);
                } else if ((subtype == 0x05 || subtype == 0x07 || subtype == 0x11) && length >= 26) {
                    int width = u16(data, p + 5);
                    int height = u16(data, p + 7);
                    if (width > 0 && height > 0) frames.add(currentFormat + " " + width + "×" + height);
                }
            }
            p += length;
        }
        if (p != data.length) truncated = true;
        StringBuilder result = new StringBuilder();
        result.append("声明格式：").append(formats.isEmpty() ? "未从描述符解析到" : join(formats));
        if (!frames.isEmpty()) result.append("\n声明尺寸：").append(join(frames));
        if (truncated) result.append("\n描述符存在截断／无效长度，已停止越界解析。");
        result.append("\n上述为设备声明能力，不代表已收到视频帧。");
        return result.toString();
    }

    private static int u16(byte[] value, int offset) {
        return (value[offset] & 255) | ((value[offset + 1] & 255) << 8);
    }

    private static String fourcc(byte[] data, int offset) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < 4; i++) {
            int value = data[offset + i] & 255;
            if (value < 32 || value > 126) return "自定义 GUID 格式";
            out.append((char) value);
        }
        return out.toString();
    }

    private static String join(Set<String> values) {
        StringBuilder out = new StringBuilder();
        for (String value : values) {
            if (out.length() > 0) out.append("、");
            out.append(value);
        }
        return out.toString();
    }

    @SuppressWarnings("deprecation")
    private static UsbDevice getDeviceExtra(Intent intent) {
        if (Build.VERSION.SDK_INT >= 33) return intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice.class);
        return intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
    }

    private void clearPendingPermission() {
        pendingDeviceId = -1;
        pendingToken = null;
        if (pendingIntent != null) pendingIntent.cancel();
        pendingIntent = null;
    }

    private void result(String status, String detail) {
        if (!closed) {
            listener.onResult(status, detail);
            log("USB " + status + " · " + detail);
        }
    }

    private void log(String line) { if (!closed) listener.onLog(line); }

    private static String message(Throwable error) {
        return error.getClass().getSimpleName() + (error.getMessage() == null ? "" : ": " + error.getMessage());
    }

    private void onMain(Runnable task) {
        if (Looper.myLooper() == Looper.getMainLooper()) task.run();
        else main.post(task);
    }

    public void close() {
        onMain(() -> {
            if (closed) return;
            closed = true;
            generation++;
            clearPendingPermission();
            if (registered) {
                try { context.unregisterReceiver(receiver); } catch (IllegalArgumentException ignored) { }
                registered = false;
            }
            io.shutdownNow();
        });
    }
}
