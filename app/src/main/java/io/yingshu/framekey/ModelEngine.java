package io.yingshu.framekey;

import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import ai.onnxruntime.NodeInfo;
import ai.onnxruntime.OnnxJavaType;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.TensorInfo;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** One joint detector with shared gun/optic features, one worker and one frame in flight. */
public final class ModelEngine implements AutoCloseable {
    public interface Listener {
        void onReady(String detail);
        void onResult(Result result);
        void onError(String detail);
    }

    public static final class Result {
        public final String gun, optic;
        public final float gunConfidence, opticConfidence;
        /** Legacy fields are always zero: the production path no longer performs a crop search. */
        public final float locatorConfidence;
        public final int opticOffsetX;
        /** Accepted optic box in padded 320x320 input coordinates, or zeros when unknown. */
        public final float opticBoxLeft, opticBoxTop, opticBoxRight, opticBoxBottom;
        public final double elapsedMs;
        /** Worker-side breakdown; excludes video decoding, UI queueing and BLE. */
        public final double preprocessMs, modelMs, postprocessMs;
        /** Local monotonic capture time; NDI uses dequeue time before native pixel conversion. */
        public final long capturedAtMs;
        private Result(ModelPostprocess.Prediction gun, ModelPostprocess.DetectionPrediction optic,
                       double elapsedMs, long capturedAtMs, double preprocessMs, double modelMs, double postprocessMs) {
            this.gun = gun.label; this.optic = optic.label;
            this.gunConfidence = gun.confidence; this.opticConfidence = optic.confidence;
            this.locatorConfidence = 0; this.opticOffsetX = 0;
            this.opticBoxLeft = optic.left; this.opticBoxTop = optic.top;
            this.opticBoxRight = optic.right; this.opticBoxBottom = optic.bottom;
            this.elapsedMs = elapsedMs; this.capturedAtMs = capturedAtMs;
            this.preprocessMs=preprocessMs;this.modelMs=modelMs;this.postprocessMs=postprocessMs;
        }
    }

    private static final String[] JOINT_CLASSES = {"VK47", "R301", "R99", "NEMESIS", "ALTERNATOR", "LSTAR", "PROWLER", "CAR", "none", "one_x", "two_x", "three_x", "four_x"};
    private static final long MIN_FRAME_INTERVAL_MS = 100;
    private final Object lock = new Object();
    private final AssetManager assets;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> new Thread(r, "Yingshu-model"));
    private InferenceRegion region;
    private int[] roiPixels;
    private int[] gunPadding;
    private byte[] keepMask;
    private String maskSha256;
    private long generation = 1, lastAcceptedAtMs = -MIN_FRAME_INTERVAL_MS;
    private boolean closed, ready, busy;
    private String readyDetail = "", lastError = "";
    // Owned and accessed only by worker; close is also queued on that same worker.
    private OrtEnvironment environment;
    private Model jointModel;
    private float gunThreshold, opticThreshold, opticConflictThreshold, opticConflictMargin;

    public ModelEngine(Context context, Listener listener) {
        if (context == null || listener == null) throw new IllegalArgumentException("Context and listener required");
        this.assets = context.getApplicationContext().getAssets();
        this.listener = listener;
        worker.execute(this::loadModels);
    }

    /** Called on the UI thread. The caller retains ownership of fullFrame and may recycle it on return. */
    public void offer(Bitmap fullFrame) {
        offerPixels(fullFrame, false, 0, 0, SystemClock.elapsedRealtime());
    }

    /** NDI's unscaled native ROI; preview scaling must never affect the model input. */
    public void offerPanel(Bitmap panel, int sourceWidth, int sourceHeight, long receivedAtMs) {
        offerPixels(panel, true, sourceWidth, sourceHeight, receivedAtMs);
    }

    private void offerPixels(Bitmap input, boolean panel, int sourceWidth, int sourceHeight, long capturedAt) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            throw new IllegalStateException("ModelEngine.offer must run on the UI thread");
        }
        final long now = SystemClock.elapsedRealtime();
        if (capturedAt > now || now - capturedAt > 200) return;
        final long ticket;
        synchronized (lock) {
            if (closed || !ready || busy || capturedAt - lastAcceptedAtMs < MIN_FRAME_INTERVAL_MS) return;
            ticket = generation;
            busy = true;
            lastAcceptedAtMs = capturedAt;
        }
        try {
            if (!panel) {
                if (input == null || input.isRecycled()) throw new IllegalArgumentException("视频帧已失效");
                sourceWidth = input.getWidth(); sourceHeight = input.getHeight();
            }
            if (sourceWidth != region.frameWidth || sourceHeight != region.frameHeight) {
                throw new IllegalArgumentException("识别需要 " + region.frameWidth + " × " + region.frameHeight + " 原始画面，当前 "
                        + sourceWidth + " × " + sourceHeight);
            }
            if (input == null || input.isRecycled()) throw new IllegalArgumentException("视频帧已失效");
            if (panel && (input.getWidth() != region.width || input.getHeight() != region.height))
                throw new IllegalArgumentException("识别区域尺寸与模型不一致");
            // Copy now: UVC/NDI and the preview are free to reuse/recycle the original Bitmap afterwards.
            // Only the manifest's small weapon panel enters inference. No surrounding framebuffer pixels are read.
            input.getPixels(roiPixels, 0, region.width, panel ? 0 : region.left, panel ? 0 : region.top, region.width, region.height);
            ImageTensor.applyMask(roiPixels, keepMask);
            synchronized (lock) {
                if (closed || generation != ticket) { busy = false; return; }
                worker.execute(() -> infer(ticket, capturedAt));
            }
        } catch (Exception error) {
            synchronized (lock) { busy = false; }
            postError(ticket, message(error));
        }
    }

    /** Invalidates all pending results/errors, including callbacks already queued on the main thread. */
    public void reset() {
        synchronized (lock) {
            if (closed) return;
            generation++;
            lastAcceptedAtMs = -MIN_FRAME_INTERVAL_MS;
            lastError = "";
            // An old inference retains its busy lease until it returns; reset must never queue another.
            if (ready) postReady(generation);
        }
    }

    @Override public void close() {
        synchronized (lock) {
            if (closed) return;
            closed = true;
            ready = false;
            generation++;
            // Do not close native sessions while run() is executing and do not abandon queued cleanup.
            worker.execute(this::closeModels);
            worker.shutdown();
        }
    }

    private void loadModels() {
        boolean loaded = false;
        try {
            JSONObject manifest = new JSONObject(new String(readAsset("manifest.json"), StandardCharsets.UTF_8));
            if (manifest.getInt("schema_version") != 6) throw new IllegalArgumentException("Unsupported joint-model manifest schema");
            region = new InferenceRegion(readInts(manifest.getJSONArray("frame_size"), 2, "frame_size"),
                    readInts(manifest.getJSONArray("roi_xywh"), 4, "roi_xywh"));
            JSONObject joint = manifest.getJSONObject("joint_detector");
            validateConfig(joint, "joint.onnx", 320, "letterbox_114", JOINT_CLASSES);
            gunPadding = readInts(joint.getJSONArray("padding_ltrb"), 4, "padding_ltrb");
            if (joint.getDouble("scale") != 1.0) throw new IllegalArgumentException("Joint ROI must use unscaled letterbox pixels");
            ImageTensor.validateLetterbox(region.width, region.height, gunPadding);
            roiPixels = new int[region.pixelCount()];
            keepMask = loadMask(manifest.getJSONObject("mask"));
            if (!Arrays.equals(readInts(joint.getJSONArray("input_shape"),4,"input_shape"),new int[]{1,3,320,320})
                    || !Arrays.equals(readInts(joint.getJSONArray("output_shape"),3,"output_shape"),new int[]{1,300,6}))
                throw new IllegalArgumentException("Unexpected joint-model tensor contract");
            gunThreshold = threshold(joint, "gun_score_threshold");
            opticThreshold = threshold(joint, "optic_score_threshold");
            opticConflictThreshold = threshold(joint, "optic_conflict_threshold");
            opticConflictMargin = threshold(joint, "optic_conflict_margin");
            environment = OrtEnvironment.getEnvironment();
            jointModel = openModel("joint.onnx", joint, 1, 320, new long[]{1, 300, 6});
            synchronized (lock) {
                if (closed) return;
                readyDetail = "ONNX Runtime " + environment.getVersion() + " CPU/2 threads; "
                        + "ROI=" + region.left + "," + region.top + "," + region.width + "," + region.height
                        + "; padding=" + Arrays.toString(gunPadding)
                        + "; mask=" + maskSha256 + "; joint=13-class detector; one session/run";
                ready = true;
                loaded = true;
                postReady(generation);
            }
        } catch (Exception | LinkageError error) {
            long ticket;
            synchronized (lock) { ready = false; ticket = generation; }
            postError(ticket, "模型加载失败：" + message(error));
        } finally {
            if (!loaded) closeModels();
        }
    }

    private void infer(long ticket, long capturedAt) {
        long started = System.nanoTime();
        try {
            if (!isCurrent(ticket)) return;
            ModelPostprocess.Prediction gun;
            ModelPostprocess.DetectionPrediction optic = ModelPostprocess.DetectionPrediction.unknown("no_gun");
            long inputReady, modelEnded;
            // Both predictions come from one shared feature extractor and one session run.
            try (OnnxTensor tensor = tensor(ImageTensor.detectorLetterbox(roiPixels, region.width, region.height, gunPadding), 1, 320)) {
                inputReady=System.nanoTime();
                try (OrtSession.Result output = jointModel.session.run(Collections.singletonMap(jointModel.inputName, tensor))) {
                    modelEnded=System.nanoTime();
                    if (!isCurrent(ticket)) return;
                    float[][] rows=detectionRows(output,"Joint");
                    gun=ModelPostprocess.gun(rows,gunThreshold);
                    if (ModelPostprocess.isKnownGun(gun.label)) {
                        optic = ModelPostprocess.jointOpticDetections(rows, gun.label,
                                opticThreshold, opticConflictThreshold, opticConflictMargin);
                    }
                }
            }
            long finished=System.nanoTime();
            Result result = new Result(gun, optic, (finished-started)/1_000_000.0,capturedAt,
                    (inputReady-started)/1_000_000.0,(modelEnded-inputReady)/1_000_000.0,(finished-modelEnded)/1_000_000.0);
            main.post(() -> {
                synchronized (lock) {
                    if (!closed && generation == ticket) { lastError = ""; listener.onResult(result); }
                }
            });
        } catch (Exception | LinkageError error) {
            postError(ticket, "推理失败：" + message(error));
        } finally {
            synchronized (lock) { busy = false; }
        }
    }

    private OnnxTensor tensor(float[] values, int batchSize, int size) throws Exception {
        return OnnxTensor.createTensor(environment, FloatBuffer.wrap(values), new long[]{batchSize, 3, size, size});
    }

    private static float[][] detectionRows(OrtSession.Result output, String model) throws Exception {
        Object value = output.get(0).getValue();
        if (!(value instanceof float[][][]) || ((float[][][]) value).length != 1) {
            throw new IllegalArgumentException(model + " detector output changed shape");
        }
        float[][] rows=((float[][][]) value)[0];
        JointOutputValidator.validate(rows);
        return rows;
    }

    private Model openModel(String file, JSONObject config, int batchSize, int size, long[] outputShape) throws Exception {
        byte[] modelBytes=readAsset(file);
        if(config.getLong("bytes")!=modelBytes.length)throw new IllegalArgumentException(file+" size mismatch");
        String expected=config.getString("sha256");
        if(!expected.matches("[0-9a-f]{64}"))throw new IllegalArgumentException(file+" invalid SHA256");
        byte[] digest=MessageDigest.getInstance("SHA-256").digest(modelBytes);
        StringBuilder actual=new StringBuilder(64);
        for(byte value:digest){actual.append(Character.forDigit((value>>>4)&15,16));actual.append(Character.forDigit(value&15,16));}
        if(!expected.contentEquals(actual))throw new IllegalArgumentException(file+" SHA256 mismatch");
        try (OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
            options.setIntraOpNumThreads(2);
            options.setInterOpNumThreads(1);
            options.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL);
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
            OrtSession session = environment.createSession(modelBytes, options);
            try {
                String input = requireTensor(session.getInputInfo(), new long[]{batchSize, 3, size, size}, file + " input");
                requireTensor(session.getOutputInfo(), outputShape, file + " output");
                return new Model(session, input);
            } catch (Exception error) {
                session.close();
                throw error;
            }
        }
    }

    private static String requireTensor(Map<String, NodeInfo> info, long[] shape, String description) {
        if (info.size() != 1) throw new IllegalArgumentException(description + " requires exactly one tensor");
        Map.Entry<String, NodeInfo> entry = info.entrySet().iterator().next();
        if (!(entry.getValue().getInfo() instanceof TensorInfo)) throw new IllegalArgumentException(description + " is not a tensor");
        TensorInfo tensor = (TensorInfo) entry.getValue().getInfo();
        if (tensor.type != OnnxJavaType.FLOAT || !Arrays.equals(tensor.getShape(), shape)) {
            throw new IllegalArgumentException(description + " expected FLOAT " + Arrays.toString(shape) + ", got " + tensor);
        }
        return entry.getKey();
    }

    private byte[] readAsset(String file) throws Exception {
        try (InputStream input = assets.open("models/" + file); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[32768];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            return output.toByteArray();
        }
    }

    /** One asset read at model initialization, never per frame. */
    private byte[] loadMask(JSONObject mask) throws Exception {
        if (!"panel-mask.bin".equals(mask.getString("file"))
                || !"u8_keep_0_or_1".equals(mask.getString("encoding"))
                || mask.getDouble("width") != region.width || mask.getDouble("height") != region.height) {
            throw new IllegalArgumentException("Mask file, encoding or dimensions do not match the inference ROI");
        }
        if (mask.has("roi_xywh") && !Arrays.equals(readInts(mask.getJSONArray("roi_xywh"), 4, "mask roi_xywh"),
                new int[]{region.left, region.top, region.width, region.height})) {
            throw new IllegalArgumentException("Mask ROI coordinates do not match the model manifest");
        }
        if (mask.has("target_frame_size") && !Arrays.equals(readInts(mask.getJSONArray("target_frame_size"), 2,
                "mask target_frame_size"), new int[]{region.frameWidth, region.frameHeight})) {
            throw new IllegalArgumentException("Mask frame size does not match the model manifest");
        }
        String expected = mask.getString("sha256");
        if (!expected.matches("[0-9a-fA-F]{64}") || !mask.getString("source_sha256").matches("[0-9a-fA-F]{64}")) {
            throw new IllegalArgumentException("Mask SHA-256 metadata is invalid");
        }
        byte[] bytes = readAsset("panel-mask.bin");
        ImageTensor.validateMask(bytes, region.pixelCount());
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        char[] hex = new char[digest.length * 2];
        char[] digits = "0123456789abcdef".toCharArray();
        for (int i = 0; i < digest.length; i++) {
            hex[2*i] = digits[(digest[i] & 255) >>> 4];
            hex[2*i+1] = digits[digest[i] & 15];
        }
        maskSha256 = new String(hex);
        if (!maskSha256.equalsIgnoreCase(expected)) throw new IllegalArgumentException("Mask SHA-256 mismatch");
        return bytes;
    }

    private void postReady(long ticket) {
        main.post(() -> { synchronized (lock) { if (!closed && generation == ticket && ready) listener.onReady(readyDetail); } });
    }

    private void postError(long ticket, String detail) {
        main.post(() -> {
            synchronized (lock) {
                if (!closed && generation == ticket && !detail.equals(lastError)) {
                    lastError = detail;
                    listener.onError(detail);
                }
            }
        });
    }

    private boolean isClosed() { synchronized (lock) { return closed; } }
    private boolean isCurrent(long ticket) { synchronized (lock) { return !closed && generation == ticket; } }

    private void closeModels() {
        closeModel(jointModel); jointModel = null;
        // OrtEnvironment is process-global; other engine instances may still use it.
        environment = null;
    }

    private static void closeModel(Model model) {
        if (model != null) {
            try { model.session.close(); }
            catch (Exception error) { Log.w("ModelEngine", "Session cleanup failed", error); }
        }
    }

    private static String message(Throwable error) {
        String detail = error.getMessage();
        return detail == null || detail.isEmpty() ? error.getClass().getSimpleName() : detail;
    }

    private static void validateConfig(JSONObject config, String file, int size, String resize, String[] labels) throws Exception {
        if (!file.equals(config.getString("file")) || config.getInt("input_size") != size
                || !resize.equals(config.getString("resize"))) throw new IllegalArgumentException("Unexpected " + file + " preprocessing");
        JSONArray classes = config.getJSONArray("classes");
        if (classes.length() != labels.length) throw new IllegalArgumentException(file + " classes mismatch");
        for (int i = 0; i < labels.length; i++) {
            if (!labels[i].equals(classes.getString(i))) throw new IllegalArgumentException(file + " class order mismatch");
        }
    }

    private static int[] readInts(JSONArray array, int count, String name) throws Exception {
        if (array.length() != count) throw new IllegalArgumentException(name + " must have " + count + " integers");
        int[] values = new int[count];
        for (int i = 0; i < count; i++) {
            values[i] = array.getInt(i);
            if (array.getDouble(i) != values[i]) throw new IllegalArgumentException(name + " must use integer pixels");
        }
        return values;
    }

    private static float threshold(JSONObject config, String key) throws Exception {
        double value = config.getDouble(key);
        if (Double.isNaN(value) || Double.isInfinite(value) || value <= 0 || value > 1) {
            throw new IllegalArgumentException("Invalid " + key);
        }
        return (float) value;
    }

    private static final class Model {
        final OrtSession session;
        final String inputName;
        Model(OrtSession session, String inputName) { this.session = session; this.inputName = inputName; }
    }
}
