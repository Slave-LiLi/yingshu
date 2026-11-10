package io.yingshu.framekey;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.os.SystemClock;
import ai.onnxruntime.OnnxJavaType;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.TensorInfo;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Collections;

/** File-only app_process test. No APK installation, Activity, capture device or BLE code. */
public final class DeviceModelSmoke {
    private static final String ROOT = "/data/local/tmp/yingshu-model-smoke-20261107";
    private static final String MASK_SHA = "0f035302b617d2cb0c62b728a4158d2eae9f1df6f5215dd51f7447238e9cfaac";

    private static boolean legacyDisabled(){return true;}

    public static void main(String[] args) throws Exception {
        if (legacyDisabled()) throw new IllegalStateException("Historical schema 3/4 harness: use the matching 0.3.6 source; never decode old models with schema 6 classes.");
        if (args.length != 1 || !new File(args[0]).getCanonicalPath().equals(ROOT))
            throw new IllegalArgumentException("Expected dedicated smoke directory");
        JSONObject report = new JSONObject();
        report.put("schema_version", 3);
        report.put("test", "Android file-only masked compact-panel two-YOLO inference");
        report.put("device_model", Build.MODEL); report.put("android_api", Build.VERSION.SDK_INT);
        report.put("android_release", Build.VERSION.RELEASE);
        report.put("application_installed_or_started", false);
        report.put("hardware_capture_or_ble_tested", false);
        report.put("scope", "Frozen 13 validation recording images with 234 synthetic transforms, not independent-video or live app acceptance.");
        report.put("timing_scope", "model_only_ms measures synchronous OrtSession.run only; excludes decoding, preprocessing, tensor creation, output extraction, hashing and postprocessing.");
        long started = SystemClock.elapsedRealtime();
        try { run(new File(ROOT), report); report.put("status", "completed"); }
        catch (Throwable error) {
            report.put("status", "failed"); report.put("error", error.toString());
            java.io.StringWriter trace = new java.io.StringWriter();
            error.printStackTrace(new java.io.PrintWriter(trace)); report.put("stacktrace", trace.toString());
        }
        report.put("total_wall_ms", SystemClock.elapsedRealtime() - started);
        Files.write(new File(ROOT, "device-detector-result.json").toPath(), (report.toString(2) + "\n").getBytes(StandardCharsets.UTF_8));
        System.out.println(report.toString());
        System.exit("completed".equals(report.getString("status")) ? 0 : 2);
    }

    private static void run(File root, JSONObject report) throws Exception {
        JSONObject manifest = read(new File(root, "models/manifest.json"));
        if (manifest.getInt("schema_version") != 3) throw new IllegalArgumentException("Expected model schema 3");
        InferenceRegion region = new InferenceRegion(ints(manifest.getJSONArray("frame_size")), ints(manifest.getJSONArray("roi_xywh")));
        if (region.frameWidth != 1920 || region.frameHeight != 1080 || region.left != 1472 || region.top != 936
                || region.width != 320 || region.height != 128) throw new IllegalArgumentException("Unexpected frozen compact ROI");
        JSONObject gunConfig = manifest.getJSONObject("gun"), opticConfig = manifest.getJSONObject("optic_detector");
        int[] padding = ints(gunConfig.getJSONArray("padding_ltrb"));
        if (!Arrays.equals(padding, new int[]{0,96,0,96}) || gunConfig.getDouble("scale") != 1.0
                || opticConfig.getDouble("scale") != 1.0 || !Arrays.equals(padding, ints(opticConfig.getJSONArray("padding_ltrb")))
                || !"letterbox_114".equals(gunConfig.getString("resize")) || !"letterbox_114".equals(opticConfig.getString("resize")))
            throw new IllegalArgumentException("Two detectors must share unscaled letterbox input");
        JSONObject maskConfig = manifest.getJSONObject("mask");
        if (!"panel-mask.bin".equals(maskConfig.getString("file")) || !"u8_keep_0_or_1".equals(maskConfig.getString("encoding"))
                || maskConfig.getInt("width") != 320 || maskConfig.getInt("height") != 128)
            throw new IllegalArgumentException("Unexpected binary mask contract");
        byte[] mask = Files.readAllBytes(new File(root, "models/panel-mask.bin").toPath());
        ImageTensor.validateMask(mask, region.pixelCount());
        String maskHash = hash(mask);
        if (!MASK_SHA.equals(maskHash) || !maskHash.equals(maskConfig.getString("sha256")))
            throw new IllegalArgumentException("Frozen mask checksum mismatch");
        byte[] gunBytes = verifiedModel(root, gunConfig), opticBytes = verifiedModel(root, opticConfig);
        report.put("gun_sha256", hash(gunBytes)); report.put("optic_sha256", hash(opticBytes)); report.put("mask_sha256", maskHash);
        report.put("model_roi_xywh", manifest.getJSONArray("roi_xywh")); report.put("model_pixel_count", region.pixelCount());
        report.put("session_options", "CPU intra_op=2 inter_op=1 SEQUENTIAL ALL_OPT"); report.put("warmup_runs_per_model", 3);
        report.put("forced_run_note", "Optic runs once for every forced-gun evaluation. Full-path decoding reuses those same rows only when gun is known; unknown gun records optic skipped and zero optic model time.");
        JSONArray cases = read(new File(root, "cases.json")).getJSONArray("cases");
        if (cases.length() != 234) throw new IllegalArgumentException("Frozen fixture count changed");
        report.put("case_count", cases.length());
        OrtEnvironment environment = OrtEnvironment.getEnvironment(); report.put("ort_version", environment.getVersion());
        long load = System.nanoTime();
        try (OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
            options.setIntraOpNumThreads(2); options.setInterOpNumThreads(1);
            options.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL);
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
            try (OrtSession gun = environment.createSession(gunBytes, options);
                 OrtSession optic = environment.createSession(opticBytes, options)) {
                String gunInput = validate(gun), opticInput = validate(optic);
                report.put("two_model_load_ms", ms(load));
                JSONArray results = new JSONArray(); report.put("cases", results);
                int forcedCorrect=0, fullCorrect=0, forcedWrong=0, fullWrong=0, exactCount=0, desktopCount=0, numericCount=0;
                for (int index=0; index<cases.length(); index++) {
                    JSONObject test=cases.getJSONObject(index), row=new JSONObject();
                    for (String key : new String[]{"id","base_id","suite","transform","gun","state","image"}) row.put(key,test.getString(key));
                    row.put("dx",test.getInt("dx"));
                    BitmapFactory.Options decode=new BitmapFactory.Options(); decode.inScaled=false; decode.inPreferredConfig=Bitmap.Config.ARGB_8888;
                    Bitmap bitmap=BitmapFactory.decodeFile(new File(root,test.getString("image")).getPath(),decode);
                    if (bitmap==null || bitmap.getWidth()!=640 || bitmap.getHeight()!=640) throw new IllegalArgumentException("Invalid frozen fixture PNG");
                    int[] original=new int[640*640]; bitmap.getPixels(original,0,640,0,0,640,640); bitmap.recycle();
                    int[] translated=translate(original,test.getInt("dx"),"attachment-band".equals(test.getString("transform")));
                    int[] pixels=panel(translated,region.left-1280,region.top-440,region.width,region.height);
                    long prepBegin=System.nanoTime(); ImageTensor.applyMask(pixels,mask);
                    float[] values=ImageTensor.detectorLetterbox(pixels,region.width,region.height,padding);
                    double preprocessMs=ms(prepBegin);
                    ModelPostprocess.Prediction gunPrediction;
                    ModelPostprocess.DetectionPrediction forced, full;
                    double gunMs, opticMs, tensorMs;
                    boolean fullCallsOptic;
                    long tensorBegin=System.nanoTime();
                    try (OnnxTensor input=OnnxTensor.createTensor(environment,FloatBuffer.wrap(values),new long[]{1,3,320,320})) {
                        tensorMs=ms(tensorBegin);
                        if (index==0) for (int warmup=0; warmup<3; warmup++) {
                            try (OrtSession.Result unused=gun.run(Collections.singletonMap(gunInput,input))) {}
                            try (OrtSession.Result unused=optic.run(Collections.singletonMap(opticInput,input))) {}
                        }
                        long begin=System.nanoTime();
                        try (OrtSession.Result output=gun.run(Collections.singletonMap(gunInput,input))) {
                            gunMs=ms(begin);
                            gunPrediction=ModelPostprocess.gun(rows(output),(float)gunConfig.getDouble("score_threshold"));
                        }
                        begin=System.nanoTime();
                        try (OrtSession.Result output=optic.run(Collections.singletonMap(opticInput,input))) {
                            opticMs=ms(begin);
                            float[][] detections=rows(output);
                            forced=optic(detections,test.getString("gun"),opticConfig);
                            fullCallsOptic=knownGun(gunPrediction.label);
                            full=fullCallsOptic ? optic(detections,gunPrediction.label,opticConfig) : ModelPostprocess.DetectionPrediction.unknown("no_gun");
                        }
                    }
                    boolean gunMatches=gunPrediction.label.equals(test.getString("gun"));
                    JSONObject forcedResult=describe(forced,test,true), fullResult=describe(full,test,gunMatches);
                    row.put("gun_prediction",gunPrediction.label); row.put("gun_confidence",gunPrediction.confidence); row.put("gun_matches",gunMatches);
                    row.put("forced_optic",forcedResult); row.put("full_pipeline",fullResult);
                    row.put("preprocess_ms",preprocessMs); row.put("tensor_create_ms",tensorMs);
                    row.put("gun_model_only_ms",gunMs); row.put("forced_optic_model_only_ms",opticMs);
                    row.put("full_optic_model_only_ms",fullCallsOptic?opticMs:0);
                    row.put("full_model_only_ms",gunMs+(fullCallsOptic?opticMs:0)); row.put("full_calls_optic",fullCallsOptic);
                    String tensorHash=hash(values); boolean exact=tensorHash.equals(test.getString("shared_tensor_sha256"));
                    row.put("shared_tensor_sha256",tensorHash); row.put("preprocessing_exact_match",exact);
                    JSONObject desktop=test.getJSONObject("desktop");
                    boolean same=gunPrediction.label.equals(desktop.getString("gun"))
                            && sameDecision(forced,desktop.getJSONObject("forced")) && sameDecision(full,desktop.getJSONObject("full"));
                    boolean close=Math.abs(gunPrediction.confidence-desktop.getDouble("gun_confidence"))<=0.0001
                            && closeValues(forced,desktop.getJSONObject("forced")) && closeValues(full,desktop.getJSONObject("full"));
                    row.put("desktop_decisions_match",same); row.put("desktop_values_close",close);
                    if(forcedResult.getBoolean("joint_match"))forcedCorrect++;
                    if(fullResult.getBoolean("joint_match"))fullCorrect++;
                    if(forcedResult.getBoolean("accepted_wrong"))forcedWrong++;
                    if(fullResult.getBoolean("accepted_wrong"))fullWrong++;
                    if(exact)exactCount++;if(same)desktopCount++;if(close)numericCount++;
                    results.put(row);
                    if(index%13==0 || forcedResult.getBoolean("accepted_wrong") || fullResult.getBoolean("accepted_wrong") || !same || !exact)
                        System.err.println("DETECTOR "+index+" "+test.getString("id")+" gun="+gunPrediction.label+" forced="+forced.label+"/"+forced.reason+" full="+full.label+" exact="+exact+" desktop="+same);
                }
                report.put("forced_state_and_slot_matches",forcedCorrect); report.put("full_gun_state_and_slot_matches",fullCorrect);
                report.put("forced_accepted_wrong",forcedWrong); report.put("full_accepted_wrong",fullWrong);
                report.put("preprocessing_exact_matches",exactCount); report.put("desktop_decision_matches",desktopCount); report.put("desktop_numeric_matches",numericCount);
            }
        }
    }

    private static ModelPostprocess.DetectionPrediction optic(float[][] rows,String gun,JSONObject config)throws Exception {
        return ModelPostprocess.opticDetections(rows,gun,(float)config.getDouble("score_threshold"),
                (float)config.getDouble("conflict_threshold"),(float)config.getDouble("conflict_margin"));
    }
    private static JSONObject describe(ModelPostprocess.DetectionPrediction p,JSONObject test,boolean gunMatches)throws Exception {
        JSONObject value=new JSONObject(); boolean accepted=p.classId>=0;
        JSONArray truth=test.getJSONArray("expected_crop_xyxy");
        double trueCenter=(truth.getDouble(0)+truth.getDouble(2))/2-192;
        double error=(p.left+p.right)/2.0-trueCenter;
        boolean state=p.label.equals(test.getString("state")), slot=accepted&&Math.abs(error)<16;
        boolean joint=gunMatches&&state&&slot;
        value.put("label",p.label);value.put("confidence",p.confidence);value.put("reason",p.reason);value.put("class_id",p.classId);
        value.put("bbox_padded_xyxy",accepted?new JSONArray(new float[]{p.left,p.top,p.right,p.bottom}):JSONObject.NULL);
        value.put("offset_error_px",accepted?error:JSONObject.NULL);value.put("state_match",state);value.put("slot_match",slot);
        value.put("within_4px",accepted&&Math.abs(error)<=4);value.put("joint_match",joint);
        value.put("accepted",accepted);value.put("accepted_wrong",accepted&&!joint);return value;
    }
    private static boolean sameDecision(ModelPostprocess.DetectionPrediction p,JSONObject expected)throws Exception {
        return p.label.equals(expected.getString("label"))&&p.reason.equals(expected.getString("reason"))&&p.classId==expected.getInt("class_id");
    }
    private static boolean closeValues(ModelPostprocess.DetectionPrediction p,JSONObject expected)throws Exception {
        if(Math.abs(p.confidence-expected.getDouble("confidence"))>0.0001)return false;
        if(p.classId<0)return true;
        JSONArray box=expected.getJSONArray("bbox_padded_xyxy");float[] actual={p.left,p.top,p.right,p.bottom};
        for(int i=0;i<4;i++)if(Math.abs(actual[i]-box.getDouble(i))>0.01)return false;
        return true;
    }
    private static boolean knownGun(String label){return "VK47".equals(label)||"R301".equals(label)||"R99".equals(label);}
    private static float[][] rows(OrtSession.Result output)throws Exception {
        Object value=output.get(0).getValue();
        if(!(value instanceof float[][][])||((float[][][])value).length!=1)throw new IllegalArgumentException("Expected [1,300,6] detector output");
        return ((float[][][])value)[0];
    }
    private static byte[] verifiedModel(File root,JSONObject config)throws Exception {
        String name=config.getString("file");
        if(!"gun.onnx".equals(name)&&!"optic-detector.onnx".equals(name))throw new IllegalArgumentException("Unexpected model filename");
        byte[] bytes=Files.readAllBytes(new File(root,"models/"+name).toPath());
        if(!hash(bytes).equals(config.getString("sha256")))throw new IllegalArgumentException("Model hash mismatch: "+name);
        return bytes;
    }
    private static int[] translate(int[] source,int dx,boolean band) {
        if(Math.abs(dx)>=640)throw new IllegalArgumentException("Fixture shift out of range");
        int[] result=source.clone();int startY=band?551:0,endY=band?590:640;
        for(int y=startY;y<endY;y++) {
            Arrays.fill(result,y*640,(y+1)*640,0xff000000);
            int sourceX=Math.max(0,-dx),targetX=Math.max(0,dx),length=640-Math.abs(dx);
            if(length>0)System.arraycopy(source,y*640+sourceX,result,y*640+targetX,length);
        }return result;
    }
    private static int[] panel(int[] source,int x,int y,int width,int height) {
        if(x<0||y<0||width<1||height<1||x+width>640||y+height>640)throw new IllegalArgumentException("Invalid fixture panel crop");
        int[] result=new int[width*height];for(int row=0;row<height;row++)System.arraycopy(source,(y+row)*640+x,result,row*width,width);return result;
    }
    private static int[] ints(JSONArray values)throws Exception{int[] result=new int[values.length()];for(int i=0;i<result.length;i++){result[i]=values.getInt(i);if(values.getDouble(i)!=result[i])throw new IllegalArgumentException("Integer coordinates required");}return result;}
    private static JSONObject read(File file)throws Exception{return new JSONObject(new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8));}
    private static double ms(long begin){return(System.nanoTime()-begin)/1_000_000.0;}
    private static String hash(float[] values)throws Exception {
        ByteBuffer buffer=ByteBuffer.allocate(values.length*4).order(ByteOrder.LITTLE_ENDIAN);buffer.asFloatBuffer().put(values);return hash(buffer.array());
    }
    private static String hash(byte[] bytes)throws Exception {
        byte[] digest=MessageDigest.getInstance("SHA-256").digest(bytes);StringBuilder text=new StringBuilder();
        for(byte value:digest)text.append(String.format(java.util.Locale.ROOT,"%02x",value&255));return text.toString();
    }
    private static String validate(OrtSession session)throws Exception {
        if(session.getNumInputs()!=1||session.getNumOutputs()!=1)throw new IllegalArgumentException("Expected one model input/output");
        String name=session.getInputNames().iterator().next();
        TensorInfo input=(TensorInfo)session.getInputInfo().get(name).getInfo();TensorInfo output=(TensorInfo)session.getOutputInfo().values().iterator().next().getInfo();
        if(input.type!=OnnxJavaType.FLOAT||output.type!=OnnxJavaType.FLOAT||!Arrays.equals(input.getShape(),new long[]{1,3,320,320})
                ||!Arrays.equals(output.getShape(),new long[]{1,300,6}))throw new IllegalArgumentException("Model shape/type mismatch");
        return name;
    }
}
