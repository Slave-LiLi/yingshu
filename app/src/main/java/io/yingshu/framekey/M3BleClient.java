package io.yingshu.framekey;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothStatusCodes;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Native Android BLE client. No application binaries or resources are reused. */
@SuppressLint("MissingPermission")
public final class M3BleClient {
    public interface Listener {
        void onState(String phase, String detail);
        void onDevice(String address, String name, int rssi);
        void onLog(String message);
        void onReady(String serial);
        void onNotification(byte[] bytes);
    }

    private static final String TAG = "YingShuBLE";
    private static final long SCAN_MS = 20_000L;
    private static final long CONNECT_MS = 15_000L;
    private static final long DISCOVERY_MS = 10_000L;
    private static final long OP_MS = 5_000L;
    private static final long SERIAL_MS = 3_000L;
    private final Context context;
    private final Listener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ArrayDeque<WriteOp> writes = new ArrayDeque<>();
    private BluetoothAdapter adapter;
    private BluetoothLeScanner scanner;
    private ScanCallback scanCallback;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic tx;
    private BluetoothGattCharacteristic rx;
    private WriteOp activeWrite;
    private Runnable operationTimeout;
    private Runnable stageTimeout;
    private Runnable scanTimeout;
    private Runnable handshakeTimeout;
    private int connectionEpoch;
    private int scanEpoch;
    private long operationId;
    private boolean closed;
    private boolean querySubmitted;
    private boolean serialReceived;
    private volatile boolean ready;
    private volatile String state = "IDLE";
    private volatile String serial = "";

    private static final class WriteOp {
        final long id;
        final byte[] bytes;
        final BluetoothGattDescriptor descriptor;
        final String label;
        final Runnable done;

        WriteOp(long id, byte[] bytes, BluetoothGattDescriptor descriptor,
                String label, Runnable done) {
            this.id = id;
            this.bytes = bytes.clone();
            this.descriptor = descriptor;
            this.label = label;
            this.done = done;
        }
    }

    public M3BleClient(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        BluetoothManager manager = (BluetoothManager) this.context.getSystemService(Context.BLUETOOTH_SERVICE);
        adapter = manager == null ? null : manager.getAdapter();
    }

    public boolean isReady() { return ready; }
    public String getState() { return state; }
    public String getSerial() { return serial; }

    public void startScan() { runMain(this::startScanMain); }
    public void stopScan() { runMain(() -> stopScanMain(true)); }
    public void connect(String address) { runMain(() -> connectMain(address)); }
    public void disconnect() {
        runMain(() -> {
            stopScanMain(false);
            releaseGatt();
            setState("DISCONNECTED", "已断开 M3");
        });
    }
    public void close() {
        runMain(() -> {
            if (closed) return;
            stopScanMain(false);
            releaseGatt();
            closed = true;
        });
    }

    /** The existing explicit manual test action. */
    public void sendTestKey(int usage) {
        runMain(() -> queueKey(usage, 0, "手动按键"));
    }

    /** Main-thread only. True means queued, not downstream execution confirmed. */
    public boolean sendMappedKey(int usage, int modifiers) {
        if (Looper.myLooper() != Looper.getMainLooper()) return false;
        return queueKey(usage, modifiers, "识别按键");
    }

    private boolean queueKey(int usage, int modifiers, String label) {
        if (closed || !ready || usage <= 0 || usage > 255 || modifiers < 0 || modifiers > 255) return false;
        if (activeWrite != null || !writes.isEmpty()) return false;
        final int keyEpoch = connectionEpoch;
        List<byte[]> frames = M3Protocol.keyFrames(usage, modifiers);
        for (int i = 0; i < frames.size(); i++) {
            final boolean last = i == frames.size() - 1;
            writes.add(new WriteOp(++operationId, frames.get(i), null,
                    label + " " + (i + 1) + "/4", last ? () -> log(
                    "四帧已完成 GATT 写入；接收端是否执行需要实测确认") : null));
        }
        pumpWrites();
        return !closed && ready && keyEpoch == connectionEpoch && activeWrite != null;
    }

    private void runMain(Runnable runnable) {
        if (Looper.myLooper() == Looper.getMainLooper()) runnable.run();
        else handler.post(runnable);
    }

    private boolean adapterUsable() {
        if (adapter == null) { setState("ERROR", "此设备不支持蓝牙"); return false; }
        try {
            if (!adapter.isEnabled()) { setState("ERROR", "蓝牙未开启，请先开启蓝牙"); return false; }
        } catch (SecurityException e) {
            setState("ERROR", "缺少蓝牙权限");
            return false;
        }
        return true;
    }

    private void startScanMain() {
        if (closed) return;
        if (gatt != null) {
            log("请先断开当前连接，再重新扫描");
            return;
        }
        stopScanMain(false);
        if (!adapterUsable()) return;
        final int token = ++scanEpoch;
        try {
            scanner = adapter.getBluetoothLeScanner();
            if (scanner == null) { setState("ERROR", "无法获取 BLE 扫描器"); return; }
            scanCallback = new ScanCallback() {
                @Override public void onScanResult(int callbackType, ScanResult result) {
                    handler.post(() -> acceptScanResult(token, result));
                }
                @Override public void onBatchScanResults(List<ScanResult> results) {
                    handler.post(() -> {
                        for (ScanResult result : results) acceptScanResult(token, result);
                    });
                }
                @Override public void onScanFailed(int errorCode) {
                    handler.post(() -> {
                        if (token != scanEpoch || closed) return;
                        stopScanMain(false);
                        setState("ERROR", "BLE 扫描失败，错误码 " + errorCode);
                    });
                }
            };
            ScanSettings settings = new ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();
            scanner.startScan(new ArrayList<>(), settings, scanCallback);
            setState("SCANNING", "正在扫描名称含 HCL 的 M3，20 秒后停止");
            scanTimeout = () -> {
                if (token != scanEpoch || closed) return;
                stopScanMain(false);
                setState("IDLE", "扫描结束，请选择发现的设备；无结果时检查供电、占用与权限");
            };
            handler.postDelayed(scanTimeout, SCAN_MS);
        } catch (SecurityException e) {
            stopScanMain(false);
            setState("ERROR", "缺少扫描权限，请授予附近设备或定位权限");
        } catch (RuntimeException e) {
            stopScanMain(false);
            setState("ERROR", "扫描启动失败：" + shortError(e));
        }
    }

    private void acceptScanResult(int token, ScanResult result) {
        if (closed || token != scanEpoch || scanCallback == null || result == null) return;
        try {
            ScanRecord record = result.getScanRecord();
            String advertised = record == null ? null : record.getDeviceName();
            String cached = result.getDevice().getName();
            String name = M3Protocol.matchesDeviceName(advertised) ? advertised : cached;
            if (!M3Protocol.matchesDeviceName(name)) return;
            if (listener != null) listener.onDevice(result.getDevice().getAddress(), name, result.getRssi());
        } catch (SecurityException e) {
            log("扫描结果读取失败：缺少蓝牙权限");
        }
    }

    private void stopScanMain(boolean notify) {
        ++scanEpoch;
        if (scanTimeout != null) handler.removeCallbacks(scanTimeout);
        scanTimeout = null;
        boolean wasScanning = scanCallback != null;
        if (scanner != null && scanCallback != null) {
            try { scanner.stopScan(scanCallback); }
            catch (RuntimeException e) { log("停止扫描：" + shortError(e)); }
        }
        scanCallback = null;
        scanner = null;
        if (notify && wasScanning && !closed) setState("IDLE", "已停止扫描");
    }

    private void connectMain(String address) {
        if (closed) return;
        stopScanMain(false);
        releaseGatt();
        if (!adapterUsable()) return;
        if (!BluetoothAdapter.checkBluetoothAddress(address)) {
            setState("ERROR", "蓝牙设备地址无效");
            return;
        }
        final int token = ++connectionEpoch;
        try {
            BluetoothDevice device = adapter.getRemoteDevice(address);
            setState("CONNECTING", "正在连接 " + address);
            gatt = device.connectGatt(context, false, new GattEvents(token), BluetoothDevice.TRANSPORT_LE);
            if (gatt == null) { fail("系统未创建 GATT 连接"); return; }
            setStageTimeout(token, "M3 连接超时", CONNECT_MS);
        } catch (SecurityException e) {
            fail("缺少蓝牙连接权限");
        } catch (RuntimeException e) {
            fail("连接失败：" + shortError(e));
        }
    }

    private final class GattEvents extends BluetoothGattCallback {
        private final int token;
        GattEvents(int token) { this.token = token; }
        private boolean current(BluetoothGatt source) {
            return !closed && token == connectionEpoch && source == gatt;
        }

        @Override public void onConnectionStateChange(BluetoothGatt source, int status, int newState) {
            handler.post(() -> {
                if (!current(source)) return;
                log("GATT 连接事件：status=" + status + ", state=" + newState);
                if (status != BluetoothGatt.GATT_SUCCESS) { fail("GATT 连接错误 " + status); return; }
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    if (!"CONNECTING".equals(state)) return;
                    clearStageTimeout();
                    setState("DISCOVERING", "蓝牙已连接，正在发现服务");
                    try {
                        if (!source.discoverServices()) { fail("服务发现请求未被系统接受"); return; }
                        setStageTimeout(token, "M3 服务发现超时", DISCOVERY_MS);
                    } catch (RuntimeException e) { fail("服务发现失败：" + shortError(e)); }
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    releaseGatt();
                    setState("DISCONNECTED", "M3 已断开；未自动重放任何按键");
                }
            });
        }

        @Override public void onServicesDiscovered(BluetoothGatt source, int status) {
            handler.post(() -> {
                if (!current(source) || !"DISCOVERING".equals(state)) return;
                clearStageTimeout();
                if (status != BluetoothGatt.GATT_SUCCESS) { fail("服务发现错误 " + status); return; }
                chooseAndSubscribe(source);
            });
        }

        @Override public void onDescriptorWrite(BluetoothGatt source, BluetoothGattDescriptor descriptor, int status) {
            handler.post(() -> {
                if (!current(source)) return;
                if (activeWrite == null || activeWrite.descriptor == null
                        || activeWrite.descriptor != descriptor) return;
                completeWrite(status);
            });
        }

        @Override public void onCharacteristicWrite(BluetoothGatt source, BluetoothGattCharacteristic characteristic, int status) {
            handler.post(() -> {
                if (!current(source)) return;
                if (activeWrite == null || activeWrite.descriptor != null || characteristic != tx) return;
                completeWrite(status);
            });
        }

        @Override public void onCharacteristicChanged(BluetoothGatt source, BluetoothGattCharacteristic characteristic) {
            // Android 13+ dispatches the value-bearing overload instead.
            if (Build.VERSION.SDK_INT >= 33) return;
            byte[] value = characteristic.getValue();
            final byte[] copy = value == null ? new byte[0] : value.clone();
            handler.post(() -> { if (current(source) && characteristic == rx) notification(copy); });
        }

        @Override public void onCharacteristicChanged(BluetoothGatt source,
                BluetoothGattCharacteristic characteristic, byte[] value) {
            final byte[] copy = value == null ? new byte[0] : value.clone();
            handler.post(() -> { if (current(source) && characteristic == rx) notification(copy); });
        }
    }

    private void chooseAndSubscribe(BluetoothGatt source) {
        BluetoothGattService service = source.getService(UUID.fromString(M3Protocol.SERVICE_UUID));
        if (service == null) { fail("已连接设备未提供 FFF0 服务，不能认定为兼容 M3"); return; }
        BluetoothGattCharacteristic firstNotify = null;
        BluetoothGattCharacteristic exactNotify = null;
        for (BluetoothGattCharacteristic characteristic : service.getCharacteristics()) {
            int props = characteristic.getProperties();
            log("FFF0 特征 " + characteristic.getUuid() + "，properties=0x" + Integer.toHexString(props));
            if ((props & (BluetoothGattCharacteristic.PROPERTY_WRITE
                    | BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) != 0) tx = characteristic;
            if ((props & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0 && firstNotify == null)
                firstNotify = characteristic;
            if (props == BluetoothGattCharacteristic.PROPERTY_NOTIFY && exactNotify == null)
                exactNotify = characteristic;
        }
        rx = exactNotify != null ? exactNotify : firstNotify;
        if (tx == null || rx == null) { fail("FFF0 服务缺少可写或 Notify 特征"); return; }
        BluetoothGattDescriptor cccd = rx.getDescriptor(UUID.fromString(M3Protocol.CCCD_UUID));
        if (cccd == null) { fail("通知特征缺少 CCCD 2902"); return; }
        try {
            if (!source.setCharacteristicNotification(rx, true)) { fail("系统未接受通知订阅"); return; }
            setState("SUBSCRIBING", "服务已发现，正在订阅设备通知");
            log("Tx=" + tx.getUuid() + ", Rx=" + rx.getUuid());
            enqueue(new byte[] {1, 0}, cccd, "订阅 CCCD", this::beginHandshake);
        } catch (RuntimeException e) { fail("通知订阅失败：" + shortError(e)); }
    }

    private void beginHandshake() {
        setState("HANDSHAKING", "通知已订阅，正在查询设备序列号");
        querySubmitted = false;
        serialReceived = false;
        enqueue(M3Protocol.serialQuery(), null, "序列号查询", null);
    }

    private void notification(byte[] bytes) {
        log("RX " + M3Protocol.hex(bytes));
        if (listener != null) listener.onNotification(bytes.clone());
        if (!"HANDSHAKING".equals(state) || !querySubmitted || serialReceived) return;
        String parsed = M3Protocol.parseSerial(bytes);
        if (parsed == null) return;
        serialReceived = true;
        serial = parsed;
        if (handshakeTimeout != null) handler.removeCallbacks(handshakeTimeout);
        handshakeTimeout = null;
        setState("HANDSHAKING", "已收到设备序列号，正在完成握手");
        enqueue(M3Protocol.handshakeFollowup(), null, "握手后续帧", () -> {
            ready = true;
            setState("READY", "M3 握手成功，已读取设备序列号");
            log("握手成功；GATT 写入完成不等于外设按键执行确认");
            if (listener != null) listener.onReady(serial);
        });
    }

    private void enqueue(byte[] bytes, BluetoothGattDescriptor descriptor, String label, Runnable done) {
        writes.add(new WriteOp(++operationId, bytes, descriptor, label, done));
        pumpWrites();
    }

    private void pumpWrites() {
        if (closed || gatt == null || activeWrite != null || writes.isEmpty()) return;
        activeWrite = writes.removeFirst();
        final WriteOp op = activeWrite;
        final int token = connectionEpoch;
        boolean accepted;
        try {
            if (op.descriptor != null) {
                if (Build.VERSION.SDK_INT >= 33) {
                    accepted = gatt.writeDescriptor(op.descriptor, op.bytes) == BluetoothStatusCodes.SUCCESS;
                } else {
                    op.descriptor.setValue(op.bytes);
                    accepted = gatt.writeDescriptor(op.descriptor);
                }
            } else {
                if (tx == null) { fail("写特征不可用"); return; }
                int writeType = (tx.getProperties() & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
                        ? BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE : BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT;
                if (Build.VERSION.SDK_INT >= 33) {
                    accepted = gatt.writeCharacteristic(tx, op.bytes, writeType) == BluetoothStatusCodes.SUCCESS;
                } else {
                    tx.setWriteType(writeType);
                    tx.setValue(op.bytes);
                    accepted = gatt.writeCharacteristic(tx);
                }
            }
            if (!accepted) { fail(op.label + "未被系统接受；未自动重试"); return; }
            if ("序列号查询".equals(op.label)) {
                querySubmitted = true;
                handshakeTimeout = () -> {
                    if (token == connectionEpoch && !serialReceived && !closed)
                        fail("已连接但未收到序列号响应：握手超时（3 秒）");
                };
                handler.postDelayed(handshakeTimeout, SERIAL_MS);
            }
            log("TX 已提交 " + op.label + " " + M3Protocol.hex(op.bytes));
            operationTimeout = () -> {
                if (token == connectionEpoch && activeWrite != null && activeWrite.id == op.id)
                    fail(op.label + "写入回调超时，结果未知；未自动重试");
            };
            handler.postDelayed(operationTimeout, OP_MS);
        } catch (SecurityException e) { fail("写入缺少蓝牙权限"); }
        catch (RuntimeException e) { fail(op.label + "失败：" + shortError(e)); }
    }

    private void completeWrite(int status) {
        if (activeWrite == null) return;
        if (operationTimeout != null) handler.removeCallbacks(operationTimeout);
        operationTimeout = null;
        WriteOp finished = activeWrite;
        activeWrite = null;
        if (status != BluetoothGatt.GATT_SUCCESS) {
            fail(finished.label + "写入错误 " + status + "；未自动重试");
            return;
        }
        log("GATT 写入完成 " + finished.label);
        if (finished.done != null) finished.done.run();
        pumpWrites();
    }

    private void setStageTimeout(int token, String message, long ms) {
        clearStageTimeout();
        stageTimeout = () -> { if (token == connectionEpoch && !closed) fail(message); };
        handler.postDelayed(stageTimeout, ms);
    }

    private void clearStageTimeout() {
        if (stageTimeout != null) handler.removeCallbacks(stageTimeout);
        stageTimeout = null;
    }

    private void releaseGatt() {
        ++connectionEpoch;
        ready = false;
        querySubmitted = false;
        serialReceived = false;
        serial = "";
        clearStageTimeout();
        if (operationTimeout != null) handler.removeCallbacks(operationTimeout);
        if (handshakeTimeout != null) handler.removeCallbacks(handshakeTimeout);
        operationTimeout = null;
        handshakeTimeout = null;
        writes.clear();
        activeWrite = null;
        BluetoothGatt old = gatt;
        gatt = null;
        tx = null;
        rx = null;
        if (old != null) {
            try { old.disconnect(); } catch (RuntimeException ignored) { }
            try { old.close(); } catch (RuntimeException ignored) { }
        }
    }

    private void fail(String detail) {
        releaseGatt();
        setState("ERROR", detail);
    }

    private void setState(String phase, String detail) {
        if (closed) return;
        state = phase;
        log(phase + ": " + detail);
        if (listener != null) listener.onState(phase, detail);
    }

    private void log(String message) {
        Log.i(TAG, message);
        if (!closed && listener != null) listener.onLog(message);
    }

    private static String shortError(Exception error) {
        String message = error.getMessage();
        return error.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }
}
