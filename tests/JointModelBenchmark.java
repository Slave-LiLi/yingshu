package io.yingshu.framekey;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.SystemClock;
import ai.onnxruntime.NodeInfo;
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
import java.util.Locale;

/** File-only paired CPU benchmark. No Activity, APK install, capture, BLE or application data access. */
public final class JointModelBenchmark {
    private static final String ROOT="/data/local/tmp/yingshu-joint-benchmark-20261109";
    private static final String MASK_SHA="0f035302b617d2cb0c62b728a4158d2eae9f1df6f5215dd51f7447238e9cfaac";
    private static final String[] JOINT_CLASSES={"VK47","R301","R99","none","one_x","two_x","three_x","four_x"};
    private static final String[] GUN_CLASSES={"VK47","R301","R99"};
    private static final String[] OPTIC_CLASSES={"none","one_x","two_x","three_x","four_x"};

    private static boolean legacyDisabled(){return true;}

    public static void main(String[] args)throws Exception {
        if (legacyDisabled()) throw new IllegalStateException("Historical schema 4 harness: use the matching 0.3.6 source; never decode old models with schema 6 classes.");
        if(args.length!=1||!new File(args[0]).getCanonicalPath().equals(ROOT))throw new IllegalArgumentException("Dedicated benchmark directory required");
        JSONObject report=new JSONObject();
        report.put("schema_version",1);
        report.put("scope","Paired file inference on frozen same-recording development cases; not independent accuracy, live NDI acceptance, or end-to-end latency.");
        report.put("application_installed_or_started",false);report.put("capture_or_ble_tested",false);
        report.put("timing_scope","full_call_ms includes panel clone/mask/letterbox, tensor creation, required model run(s), extraction/selection and close; excludes file decode, canonical input hash, JSON serialization and result verification.");
        report.put("session_options","CPU intra_op=2 inter_op=1 SEQUENTIAL ALL_OPT");
        long started=SystemClock.elapsedRealtime();
        try { run(new File(ROOT),report);report.put("status","completed"); }
        catch(Throwable error){report.put("status","failed");report.put("error",error.toString());error.printStackTrace(System.err);}
        report.put("total_wall_ms",SystemClock.elapsedRealtime()-started);
        Files.write(new File(ROOT,"benchmark-result.json").toPath(),(report.toString(2)+"\n").getBytes(StandardCharsets.UTF_8));
        System.out.println("JOINT_BENCHMARK "+report.getString("status"));
        System.exit("completed".equals(report.getString("status"))?0:2);
    }

    private static void run(File root,JSONObject report)throws Exception {
        JSONObject plan=read(new File(root,"benchmark.json"));
        if(plan.getInt("schema_version")!=1)throw new IllegalArgumentException("Benchmark schema mismatch");
        if(!Arrays.equals(ints(plan.getJSONArray("roi_xywh")),new int[]{1472,936,320,128})
                ||!Arrays.equals(ints(plan.getJSONArray("padding_ltrb")),new int[]{0,96,0,96}))throw new IllegalArgumentException("Frozen preprocessing contract changed");
        byte[] mask=Files.readAllBytes(new File(root,"panel-mask.bin").toPath());ImageTensor.validateMask(mask,40960);
        if(!MASK_SHA.equals(hash(mask)))throw new IllegalArgumentException("Mask checksum mismatch");
        report.put("mask_sha256",hash(mask));report.put("plan_sha256",hash(Files.readAllBytes(new File(root,"benchmark.json").toPath())));
        JSONObject manifests=plan.getJSONObject("manifest_hashes");
        for(String name:new String[]{"baseline/manifest.json","joint/manifest.json"})
            if(!hash(Files.readAllBytes(new File(root,name).toPath())).equals(manifests.getString(name)))throw new IllegalArgumentException("Manifest checksum mismatch: "+name);
        JSONObject models=plan.getJSONObject("models");
        byte[] gunBytes=modelBytes(root,models.getJSONObject("gun"),GUN_CLASSES,"baseline/gun.onnx");
        byte[] opticBytes=modelBytes(root,models.getJSONObject("optic"),OPTIC_CLASSES,"baseline/optic-detector.onnx");
        byte[] jointBytes=modelBytes(root,models.getJSONObject("joint"),JOINT_CLASSES,"joint/joint.onnx");
        report.put("model_sha256",new JSONObject().put("gun",hash(gunBytes)).put("optic",hash(opticBytes)).put("joint",hash(jointBytes)));
        JSONObject thresholds=plan.getJSONObject("thresholds");
        float gt=threshold(thresholds,"gun"),ot=threshold(thresholds,"optic"),ct=threshold(thresholds,"conflict"),cm=threshold(thresholds,"margin");
        JSONArray cases=plan.getJSONArray("cases");int repeats=plan.getInt("repeats"),warmups=plan.getInt("warmup_pairs");
        if(cases.length()!=20||repeats!=3||warmups!=3)throw new IllegalArgumentException("Frozen paired benchmark schedule changed");
        report.put("unique_case_count",cases.length());report.put("repeats",repeats);report.put("warmup_pairs",warmups);
        report.put("order_policy","Deterministic shuffled case order; alternate baseline-first/joint-first per pair and across rounds.");
        JSONArray results=new JSONArray();report.put("pairs",results);
        OrtEnvironment environment=OrtEnvironment.getEnvironment();report.put("ort_version",environment.getVersion());
        try(OrtSession.SessionOptions options=new OrtSession.SessionOptions()){
            options.setIntraOpNumThreads(2);options.setInterOpNumThreads(1);
            options.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL);
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
            long load=System.nanoTime();
            try(OrtSession gun=environment.createSession(gunBytes,options);OrtSession optic=environment.createSession(opticBytes,options);
                OrtSession joint=environment.createSession(jointBytes,options)){
                String gn=validate(gun),on=validate(optic),jn=validate(joint);
                report.put("three_sessions_load_ms",ms(load));
                int[] warmPanel=panel(root,cases.getJSONObject(0));
                for(int i=0;i<warmups;i++){
                    if(i%2==0){infer(false,warmPanel,mask,gun,gn,optic,on,joint,jn,gt,ot,ct,cm);infer(true,warmPanel,mask,gun,gn,optic,on,joint,jn,gt,ot,ct,cm);}
                    else{infer(true,warmPanel,mask,gun,gn,optic,on,joint,jn,gt,ot,ct,cm);infer(false,warmPanel,mask,gun,gn,optic,on,joint,jn,gt,ot,ct,cm);}
                }
                for(int repeat=0;repeat<repeats;repeat++)for(int i=0;i<cases.length();i++){
                    JSONObject test=cases.getJSONObject(i);int[] pixels=panel(root,test);float[] canonical=prepare(pixels,mask);
                    String tensorSha=hash(canonical);
                    if(!tensorSha.equals(test.getString("shared_tensor_sha256")))throw new IllegalArgumentException("Canonical input differs from frozen case "+test.getString("id"));
                    boolean jointFirst=(repeat+i)%2!=0;
                    Trial first=infer(jointFirst,pixels,mask,gun,gn,optic,on,joint,jn,gt,ot,ct,cm);
                    Trial second=infer(!jointFirst,pixels,mask,gun,gn,optic,on,joint,jn,gt,ot,ct,cm);
                    Trial base=jointFirst?second:first,combined=jointFirst?first:second;
                    if(!Arrays.equals(base.input,canonical)||!Arrays.equals(combined.input,canonical))throw new IllegalArgumentException("Variant input mismatch");
                    JSONObject row=new JSONObject();
                    for(String key:new String[]{"id","base_id","gun","state","suite","transform","split"})row.put(key,test.getString(key));
                    row.put("dx",test.getInt("dx"));row.put("repeat",repeat);row.put("first",jointFirst?"joint":"baseline");
                    row.put("input_exact",true);row.put("shared_tensor_sha256",tensorSha);
                    row.put("baseline",describe(base,test));row.put("joint",describe(combined,test));results.put(row);
                    if(i%13==0)System.err.println("PAIR "+repeat+" "+i+"/"+cases.length()+" baseline="+base.gun.label+"/"+base.optic.label+" joint="+combined.gun.label+"/"+combined.optic.label);
                }
            }
        }
    }

    private static final class Trial {
        ModelPostprocess.Prediction gun;ModelPostprocess.DetectionPrediction optic;
        float[] input;double prepMs,tensorMs,runMs,postMs,fullMs;int calls;
    }
    private static Trial infer(boolean combined,int[] panel,byte[] mask,OrtSession gun,String gn,OrtSession optic,String on,
                               OrtSession joint,String jn,float gt,float ot,float ct,float cm)throws Exception {
        Trial t=new Trial();long full=System.nanoTime(),start=full;
        t.input=prepare(panel,mask);t.prepMs=ms(start);start=System.nanoTime();
        try(OnnxTensor tensor=OnnxTensor.createTensor(OrtEnvironment.getEnvironment(),FloatBuffer.wrap(t.input),new long[]{1,3,320,320})){
            t.tensorMs=ms(start);t.optic=ModelPostprocess.DetectionPrediction.unknown("no_gun");
            start=System.nanoTime();
            try(OrtSession.Result output=(combined?joint:gun).run(Collections.singletonMap(combined?jn:gn,tensor))){
                t.runMs+=ms(start);t.calls++;start=System.nanoTime();
                float[][] detections=rows(output);t.gun=ModelPostprocess.gun(detections,gt);
                if(combined&&known(t.gun.label))t.optic=ModelPostprocess.jointOpticDetections(detections,t.gun.label,ot,ct,cm);
                t.postMs+=ms(start);
            }
            if(!combined&&known(t.gun.label)){
                start=System.nanoTime();
                try(OrtSession.Result output=optic.run(Collections.singletonMap(on,tensor))){
                    t.runMs+=ms(start);t.calls++;start=System.nanoTime();
                    t.optic=ModelPostprocess.opticDetections(rows(output),t.gun.label,ot,ct,cm);t.postMs+=ms(start);
                }
            }
        }
        t.fullMs=ms(full);return t;
    }
    private static float[] prepare(int[] panel,byte[] mask){
        int[] copy=panel.clone();ImageTensor.applyMask(copy,mask);return ImageTensor.detectorLetterbox(copy,320,128,new int[]{0,96,0,96});
    }
    private static JSONObject describe(Trial t,JSONObject test)throws Exception {
        JSONObject r=new JSONObject();boolean accepted=t.optic.classId>=0;
        JSONArray truth=test.getJSONArray("expected_crop_xyxy");double center=(truth.getDouble(0)+truth.getDouble(2))/2-192;
        double error=(t.optic.left+t.optic.right)/2-center;boolean slot=accepted&&Math.abs(error)<16;
        boolean gunMatch=t.gun.label.equals(test.getString("gun")),stateMatch=t.optic.label.equals(test.getString("state"));
        boolean correct=gunMatch&&stateMatch&&slot;
        r.put("gun",t.gun.label);r.put("gun_confidence",t.gun.confidence);r.put("optic",t.optic.label);r.put("optic_confidence",t.optic.confidence);
        r.put("reason",t.optic.reason);r.put("class_id",t.optic.classId);r.put("gun_match",gunMatch);r.put("state_match",stateMatch);
        r.put("slot_match",slot);r.put("correct",correct);r.put("accepted_wrong",accepted&&!correct);r.put("unknown",!accepted);
        r.put("bbox_padded_xyxy",accepted?new JSONArray(new float[]{t.optic.left,t.optic.top,t.optic.right,t.optic.bottom}):JSONObject.NULL);
        r.put("slot_center_error_px",accepted?error:JSONObject.NULL);r.put("model_calls",t.calls);
        r.put("preprocessing_ms",t.prepMs);r.put("tensor_create_ms",t.tensorMs);r.put("model_run_ms",t.runMs);
        r.put("output_postprocess_ms",t.postMs);r.put("full_call_ms",t.fullMs);return r;
    }
    private static int[] panel(File root,JSONObject test)throws Exception {
        String name=test.getString("image");File file=new File(root,name);
        if(!name.startsWith("images/")||!file.getCanonicalPath().startsWith(ROOT+"/images/"))throw new IllegalArgumentException("Invalid fixture path");
        if(!hash(Files.readAllBytes(file.toPath())).equals(test.getString("image_sha256")))throw new IllegalArgumentException("Image checksum mismatch");
        BitmapFactory.Options options=new BitmapFactory.Options();options.inScaled=false;options.inPreferredConfig=Bitmap.Config.ARGB_8888;
        Bitmap b=BitmapFactory.decodeFile(file.getPath(),options);if(b==null||b.getWidth()!=640||b.getHeight()!=640)throw new IllegalArgumentException("Expected frozen 640 ROI PNG");
        int[] pixels=new int[640*640];b.getPixels(pixels,0,640,0,0,640,640);b.recycle();
        int dx=test.getInt("dx");if(Math.abs(dx)>32)throw new IllegalArgumentException("Selected shifts must stay within32px");
        int[] shifted=pixels.clone();boolean band="attachment-band".equals(test.getString("transform"));int y1=band?551:0,y2=band?590:640;
        for(int y=y1;y<y2;y++){Arrays.fill(shifted,y*640,(y+1)*640,0xff000000);System.arraycopy(pixels,y*640+Math.max(0,-dx),shifted,y*640+Math.max(0,dx),640-Math.abs(dx));}
        int[] panel=new int[40960];for(int y=0;y<128;y++)System.arraycopy(shifted,(y+496)*640+192,panel,y*320,320);return panel;
    }
    private static byte[] modelBytes(File root,JSONObject config,String[] classes,String allowedFile)throws Exception {
        if(!allowedFile.equals(config.getString("file")))throw new IllegalArgumentException("Unexpected model path");
        JSONArray actual=config.getJSONArray("classes");if(actual.length()!=classes.length)throw new IllegalArgumentException("Model class count mismatch");
        for(int i=0;i<classes.length;i++)if(!classes[i].equals(actual.getString(i)))throw new IllegalArgumentException("Model class order mismatch");
        byte[] bytes=Files.readAllBytes(new File(root,allowedFile).toPath());
        if(bytes.length!=config.getInt("bytes")||!hash(bytes).equals(config.getString("sha256")))throw new IllegalArgumentException("Model checksum mismatch");
        return bytes;
    }
    private static String validate(OrtSession session)throws Exception {
        if(session.getNumInputs()!=1||session.getNumOutputs()!=1)throw new IllegalArgumentException("Expected single input/output");
        String name=session.getInputNames().iterator().next();NodeInfo in=session.getInputInfo().get(name),out=session.getOutputInfo().values().iterator().next();
        if(!(in.getInfo() instanceof TensorInfo)||!(out.getInfo() instanceof TensorInfo))throw new IllegalArgumentException("Expected tensors");
        TensorInfo i=(TensorInfo)in.getInfo(),o=(TensorInfo)out.getInfo();
        if(i.type!=OnnxJavaType.FLOAT||o.type!=OnnxJavaType.FLOAT||!Arrays.equals(i.getShape(),new long[]{1,3,320,320})||!Arrays.equals(o.getShape(),new long[]{1,300,6}))throw new IllegalArgumentException("Strict model shape/type mismatch");
        return name;
    }
    private static float[][] rows(OrtSession.Result output)throws Exception {Object value=output.get(0).getValue();if(!(value instanceof float[][][])||((float[][][])value).length!=1)throw new IllegalArgumentException("Output tensor mismatch");return ((float[][][])value)[0];}
    private static boolean known(String gun){return "VK47".equals(gun)||"R301".equals(gun)||"R99".equals(gun);}
    private static float threshold(JSONObject config,String key)throws Exception {double value=config.getDouble(key);if(!Double.isFinite(value)||value<=0||value>1)throw new IllegalArgumentException("Invalid threshold");return(float)value;}
    private static int[] ints(JSONArray array)throws Exception {int[] r=new int[array.length()];for(int i=0;i<r.length;i++){r[i]=array.getInt(i);if(r[i]!=array.getDouble(i))throw new IllegalArgumentException("Expected integer");}return r;}
    private static JSONObject read(File path)throws Exception{return new JSONObject(new String(Files.readAllBytes(path.toPath()),StandardCharsets.UTF_8));}
    private static double ms(long start){return(System.nanoTime()-start)/1_000_000.0;}
    private static String hash(float[] input)throws Exception{ByteBuffer bytes=ByteBuffer.allocate(input.length*4).order(ByteOrder.LITTLE_ENDIAN);bytes.asFloatBuffer().put(input);return hash(bytes.array());}
    private static String hash(byte[] input)throws Exception{StringBuilder text=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(input))text.append(String.format(Locale.ROOT,"%02x",b&255));return text.toString();}
}
