import io.yingshu.framekey.ImageTensor;
import io.yingshu.framekey.ModelPostprocess;
import io.yingshu.framekey.InferenceRegion;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.util.Arrays;

/** Run without Android/ORT. Optional directory argument writes tensors for Python differential testing. */
public final class ModelPipelineTest {
    private static int assertions;
    private static void check(boolean value, String name) {
        assertions++;
        if (!value) throw new AssertionError(name);
    }
    private static void near(float actual, double expected, double tolerance, String name) {
        check(Math.abs(actual - expected) <= tolerance, name + ": " + actual + " != " + expected);
    }
    private static void rejects(Runnable action, String name) {
        boolean rejected = false;
        try { action.run(); } catch (IllegalArgumentException expected) { rejected = true; }
        check(rejected, name);
    }
    private static float[][] rows(float[]... values) {
        float[][] rows = new float[300][6];
        for (int i = 0; i < values.length; i++) rows[i] = values[i];
        return rows;
    }
    private static float[] box(float score, float id) { return new float[]{1, 2, 30, 40, score, id}; }

    public static void main(String[] args) throws Exception {
        preprocessing();
        decoding();
        locatorPreprocessing();
        locatorDecoding();
        panelPreprocessing();
        opticDetectorDecoding();
        masking();
        if (args.length >= 1) fixtures(new File(args[0]), args.length >= 2 ? new File(args[1]) : null);
        System.out.println("ModelPipelineTest: " + assertions + " assertions passed");
    }

    private static void preprocessing() {
        int[] roi = new int[640 * 640];
        Arrays.fill(roi, 0xff123456);
        float[] gun = ImageTensor.detector(roi);
        check(gun.length == 3 * 320 * 320, "gun shape");
        near(gun[0], 0x12 / 255.0, 1e-7, "gun red plane");
        near(gun[320 * 320], 0x34 / 255.0, 1e-7, "gun green plane");
        near(gun[2 * 320 * 320], 0x56 / 255.0, 1e-7, "gun blue plane");
        float[] optic = ImageTensor.optic(roi, new int[]{263, 555, 299, 590});
        check(optic.length == 3 * 64 * 64, "optic shape");
        near(optic[0], 0x12 / 255.0, 1e-7, "optic red plane");
        near(optic[64 * 64], 0x34 / 255.0, 1e-7, "optic green plane");
        near(optic[2 * 64 * 64], 0x56 / 255.0, 1e-7, "optic blue plane");
        Arrays.fill(roi, 0xff000000);
        roi[0] = 0xff02060a;
        gun = ImageTensor.detector(roi);
        near(gun[0], 1 / 255.0, 1e-7, "opencv rounds 0.5 up");
        near(gun[320 * 320], 2 / 255.0, 1e-7, "opencv rounds 1.5 up");
        near(gun[2 * 320 * 320], 3 / 255.0, 1e-7, "opencv rounds 2.5 up");
        int[] image = new int[16];
        Arrays.fill(image, 0xffffffff);
        image[5] = 0xff000000; image[6] = 0xff400000;
        image[9] = 0xff004000; image[10] = 0xff404000;
        int[] scaled = ImageTensor.pillowBilinear(image, 4, 4, new int[]{1, 1, 3, 3}, 4, 4);
        check(scaled[0] == image[5] && scaled[3] == image[6] && scaled[12] == image[9]
                && scaled[15] == image[10], "crop clamps at its own edges, not outside white pixels");
        check(scaled[5] == 0xff101000, "PIL half-pixel centers");
        rejects(() -> ImageTensor.detector(new int[10]), "wrong ROI rejected");
        rejects(() -> ImageTensor.optic(roi, new int[]{263, 555, 263, 590}), "empty crop rejected");
        rejects(() -> ImageTensor.optic(roi, new int[]{-1, 1, 4, 4}), "out of bounds crop rejected");
    }

    private static void decoding() {
        check("".equals(ModelPostprocess.gun(rows(), .25f).label), "zero-padded no detection");
        check("".equals(ModelPostprocess.gun(rows(box(.249f, 0)), .25f).label), "below threshold rejected");
        check("VK47".equals(ModelPostprocess.gun(rows(box(.25f, 0)), .25f).label), "threshold inclusive");
        check("R301".equals(ModelPostprocess.gun(rows(box(.95f, 1), box(.8f, 0)), .25f).label), "highest score wins");
        check("R99".equals(ModelPostprocess.gun(rows(box(.95f, 2)), .25f).label), "R99 class id");
        check("unknown".equals(ModelPostprocess.gun(rows(box(.9f, 2), box(.85f, 1)), .25f).label), "conflicting high scores reject");
        check("R301".equals(ModelPostprocess.gun(rows(box(.9f, 1), box(.89f, 1)), .25f).label), "same-class duplicates not conflict");
        check("".equals(ModelPostprocess.gun(rows(box(Float.NaN, 1), box(1.01f, 1), box(.9f, 8), box(.9f, 1.5f)), .25f).label), "invalid scores and classes rejected");
        float[] invalidBox = box(.9f, 0); invalidBox[2] = -1;
        check("".equals(ModelPostprocess.gun(rows(invalidBox), .25f).label), "inverted box rejected");
        rejects(() -> ModelPostprocess.gun(new float[299][6], .25f), "wrong gun row count rejected");
        rejects(() -> ModelPostprocess.gun(rows(new float[5]), .25f), "wrong gun row width rejected");
        String[] two = {"none", "one_x"};
        String[] five = {"none", "one_x", "two_x", "three_x", "four_x"};
        ModelPostprocess.Prediction result = ModelPostprocess.optic(new float[]{0, 2}, two, .8f);
        check("one_x".equals(result.label), "R99 output mapping");
        near(result.confidence, .8807970779, 1e-6, "softmax not raw logit");
        check("unknown".equals(ModelPostprocess.optic(new float[]{1, 1}, two, .8f).label), "uncertain logits rejected");
        check("none".equals(ModelPostprocess.optic(new float[]{10000, 9990}, two, .8f).label), "positive overflow avoided");
        check("one_x".equals(ModelPostprocess.optic(new float[]{-10000, -9990}, two, .8f).label), "negative underflow avoided");
        check("four_x".equals(ModelPostprocess.optic(new float[]{-1, -1, -1, -1, 8}, five, .8f).label), "five-class order");
        check("unknown".equals(ModelPostprocess.optic(new float[]{Float.NaN, 2}, two, .8f).label), "NaN fails closed");
        check("unknown".equals(ModelPostprocess.optic(new float[]{Float.POSITIVE_INFINITY, 2}, two, .8f).label), "infinite logits fail closed");
        rejects(() -> ModelPostprocess.optic(new float[]{1, 2}, five, .8f), "wrong classifier class count rejected");
    }

    private static void locatorPreprocessing() {
        int[] offsets = ImageTensor.horizontalOffsets(-40, 40, 4);
        check(offsets.length == 21 && offsets[0] == -40 && offsets[10] == 0 && offsets[20] == 40,
                "21 ascending horizontal candidates");
        int[] image = new int[640 * 640];
        for (int y = 0; y < 640; y++) for (int x = 0; x < 640; x++) {
            image[y * 640 + x] = 0xff000000 | ((x & 255) << 16) | ((y & 255) << 8) | ((x * y) & 255);
        }
        int[] crop = {263, 555, 299, 590};
        int candidateSize = 3 * 64 * 64;
        float[] candidates = ImageTensor.opticCandidates(image, crop, offsets);
        check(candidates.length == 21 * candidateSize, "locator batch NCHW shape");
        for (int i = 0; i < 21; i++) {
            near(candidates[i * candidateSize], ((crop[0] + offsets[i]) & 255) / 255.0, 1e-7,
                    "candidate " + i + " horizontal pixel coordinate");
            near(candidates[i * candidateSize + 64 * 64], (crop[1] & 255) / 255.0, 1e-7,
                    "candidate " + i + " vertical position unchanged");
        }
        float[] original = ImageTensor.optic(image, crop);
        float[] movedLeft = ImageTensor.opticCandidates(shiftImage(image, -32), crop, offsets);
        float[] movedRight = ImageTensor.opticCandidates(shiftImage(image, 32), crop, offsets);
        check(Arrays.equals(original, Arrays.copyOfRange(movedLeft, 2 * candidateSize, 3 * candidateSize)),
                "minus 32 pixel displacement recovered by candidate 2");
        check(Arrays.equals(original, Arrays.copyOfRange(movedRight, 18 * candidateSize, 19 * candidateSize)),
                "plus 32 pixel displacement recovered by candidate 18");
        check(!Arrays.equals(original, Arrays.copyOfRange(movedRight, 10 * candidateSize, 11 * candidateSize)),
                "fixed nominal crop does not accidentally pass shift test");
        rejects(() -> ImageTensor.opticCandidates(image, new int[]{20, 555, 56, 590}, offsets),
                "search crossing ROI edge rejected instead of padded");
        rejects(() -> ImageTensor.opticCandidates(image, crop, new int[21]), "duplicate offsets rejected");
        rejects(() -> ImageTensor.horizontalOffsets(-40, 40, 3), "search must land on both endpoints");
    }

    private static int[] shiftImage(int[] image, int dx) {
        int[] shifted = new int[image.length];
        for (int y = 0; y < 640; y++) for (int x = 0; x < 640; x++) {
            int sourceX = x - dx;
            if (sourceX >= 0 && sourceX < 640) shifted[y * 640 + x] = image[y * 640 + sourceX];
        }
        return shifted;
    }

    private static float[][] backgroundRows() {
        float[][] rows = new float[21][6];
        for (float[] row : rows) row[5] = 8;
        return rows;
    }

    private static float[] locatorRow(int id, float logit) {
        float[] row = new float[6]; row[id] = logit; return row;
    }

    private static ModelPostprocess.LocatorPrediction locate(float[][] logits, String gun) {
        return ModelPostprocess.locate(logits, gun, ImageTensor.horizontalOffsets(-40, 40, 4), .8f, .1f, .8f, .1f);
    }

    private static void locatorDecoding() {
        float[][] logits = backgroundRows();
        ModelPostprocess.LocatorPrediction result = locate(logits, "VK47");
        check("unknown".equals(result.label) && result.candidateIndex == -1 && result.offsetX == 0,
                "all background cannot report a slot");
        logits[2] = locatorRow(1, 8);
        result = locate(logits, "VK47");
        check("one_x".equals(result.label) && result.offsetX == -32 && result.candidateIndex == 2,
                "left shifted slot selected");
        check(result.locatorConfidence >= result.confidence, "slot probability includes all five optic classes");
        logits = backgroundRows(); logits[18] = locatorRow(4, 8);
        result = locate(logits, "R301");
        check("four_x".equals(result.label) && result.offsetX == 32, "right shifted slot selected");
        result = locate(logits, "R99");
        check("unknown".equals(result.label) && "unsupported_r99_state".equals(result.reason),
                "R99 forbidden class rejected");
        logits[10] = locatorRow(1, 4);
        result = locate(logits, "R99");
        check("unknown".equals(result.label), "R99 must not fall back to a weaker allowed candidate");
        logits = backgroundRows(); logits[2] = locatorRow(1, 8); logits[18] = locatorRow(4, 8);
        result = locate(logits, "R301");
        check("unknown".equals(result.label) && "conflicting_candidates".equals(result.reason)
                && result.offsetX == 0 && result.candidateIndex == -1, "conflicting neighboring states rejected");
        logits = backgroundRows(); logits[2] = locatorRow(1, 8); logits[18] = locatorRow(1, 8);
        result = locate(logits, "VK47");
        check("one_x".equals(result.label) && result.offsetX == -32, "same-class ties prefer smaller signed offset");
        logits[10] = locatorRow(1, 8);
        result = locate(logits, "VK47");
        check(result.offsetX == 0 && result.candidateIndex == 10, "same-class tie prefers nominal position");
        logits = backgroundRows(); logits[10] = locatorRow(1, 2);
        result = locate(logits, "R99");
        check("unknown".equals(result.label) && "low_probability".equals(result.reason), "weak nonbackground rejected");
        logits = backgroundRows(); logits[10] = new float[]{0, 4, 0, 0, 0, 3.99f};
        result = ModelPostprocess.locate(logits, "R99", ImageTensor.horizontalOffsets(-40, 40, 4), .4f, .1f, .8f, .1f);
        check("unknown".equals(result.label) && "background_margin".equals(result.reason), "background margin enforced");
        logits = backgroundRows(); logits[10] = locatorRow(0, 8);
        result = locate(logits, "R99");
        check("none".equals(result.label), "none is a real slot class, not background");
        logits[20][4] = Float.NaN;
        result = locate(logits, "R99");
        check("unknown".equals(result.label) && "nonfinite_or_invalid_output".equals(result.reason), "one corrupt candidate rejects entire result");
        check("unknown".equals(locate(backgroundRows(), "").label), "locator cannot invent a gun");
        rejects(() -> locate(new float[20][6], "VK47"), "locator wrong batch rejected");
        rejects(() -> locate(new float[21][5], "VK47"), "locator missing background class rejected");
    }

    private static void panelPreprocessing() {
        InferenceRegion region = new InferenceRegion(new int[]{1920, 1080}, new int[]{1472, 936, 320, 128});
        check(region.pixelCount() == 40960 && region.left == 1472 && region.top == 936,
                "only the small weapon panel is copied");
        rejects(() -> new InferenceRegion(new int[]{1920, 1080}, new int[]{1700, 936, 320, 128}), "ROI right edge rejected");
        rejects(() -> new InferenceRegion(new int[]{1920, 1080}, new int[]{1472, 1000, 320, 128}), "ROI bottom edge rejected");
        rejects(() -> new InferenceRegion(new int[]{1920, 1080}, new int[]{-1, 936, 320, 128}), "negative ROI origin rejected");
        rejects(() -> new InferenceRegion(new int[]{1920, 1080}, new int[]{1472, 936, 0, 128}), "zero ROI width rejected");
        rejects(() -> new InferenceRegion(new int[]{Integer.MAX_VALUE, Integer.MAX_VALUE},
                new int[]{0, 0, Integer.MAX_VALUE, Integer.MAX_VALUE}), "ROI pixel count overflow rejected");
        int[] oldRoi = new int[640 * 640];
        for (int y = 0; y < 640; y++) for (int x = 0; x < 640; x++) {
            oldRoi[y * 640 + x] = 0xff000000 | ((x & 255) << 16) | ((y & 255) << 8) | ((x * y) & 255);
        }
        int[] panel = panelPixels(oldRoi);
        float[] tensor = ImageTensor.detectorLetterbox(panel, 320, 128, new int[]{0, 96, 0, 96});
        check(tensor.length == 3 * 320 * 320, "letterbox detector shape");
        int pixelErrors = 0;
        for (int channel = 0; channel < 3; channel++) for (int y = 0; y < 320; y++) for (int x = 0; x < 320; x++) {
            int expected = 114;
            if (y >= 96 && y < 224) expected = (panel[(y-96)*320+x] >> (16-channel*8)) & 255;
            if (tensor[channel*320*320+y*320+x] != expected/255.0f) pixelErrors++;
        }
        check(pixelErrors == 0, "all source pixels preserved exactly and every padding pixel is RGB114");
        rejects(() -> ImageTensor.detectorLetterbox(panel, 320, 128, new int[]{0, 95, 0, 96}), "wrong padding cannot silently resize");
        rejects(() -> ImageTensor.detectorLetterbox(panel, 320, 128, new int[]{-1, 96, 1, 96}), "negative padding rejected");
        rejects(() -> ImageTensor.detectorLetterbox(panel, 319, 128, new int[]{0, 96, 1, 96}), "ROI array dimensions strictly match");
        int[] offsets = ImageTensor.horizontalOffsets(-40, 40, 4);
        int[][] oldCrops = {{263,555,299,590},{295,555,331,590},{285,551,330,590}};
        int[][] newCrops = {{71,59,107,94},{103,59,139,94},{93,55,138,94}};
        for (int i = 0; i < 3; i++) {
            check(Arrays.equals(ImageTensor.opticCandidates(oldRoi, oldCrops[i], offsets),
                    ImageTensor.opticCandidates(panel, 320, 128, newCrops[i], offsets)),
                    "all 21 optic candidates unchanged after panel crop for gun " + i);
        }
        float[] band = ImageTensor.cropTensor(panel, 320, 128, new int[]{8,54,200,100}, 192, 64);
        check(band.length == 3 * 64 * 192, "rectangular optic band NCHW shape");
        rejects(() -> ImageTensor.cropTensor(panel, 320, 128, new int[]{8,54,200,130}, 192, 64),
                "rectangular crop cannot read outside the small panel");
    }

    private static int[] panelPixels(int[] roi) {
        int[] panel = new int[320 * 128];
        for (int y = 0; y < 128; y++) System.arraycopy(roi, (y+496)*640+192, panel, y*320, 320);
        return panel;
    }

    private static ModelPostprocess.DetectionPrediction opticDetector(float[][] detections, String gun) {
        return ModelPostprocess.opticDetections(detections, gun, .8f, .8f, .1f);
    }

    private static void opticDetectorDecoding() {
        ModelPostprocess.DetectionPrediction p = opticDetector(rows(), "VK47");
        check("unknown".equals(p.label) && "no_detection".equals(p.reason) && p.classId == -1,
                "empty optic detections reject");
        check("unknown".equals(opticDetector(rows(box(.799f, 1)), "VK47").label), "weak optic detection rejected");
        p = opticDetector(rows(box(.8f, 1)), "VK47");
        check("one_x".equals(p.label) && p.classId == 1 && p.confidence == .8f, "detector threshold inclusive without softmax");
        check(p.left == 1 && p.top == 2 && p.right == 30 && p.bottom == 40, "accepted model-input coordinates retained");
        p = opticDetector(rows(box(.96f, 4), box(.8f, 2)), "R301");
        check("four_x".equals(p.label), "strongest optic detection class order");
        p = opticDetector(rows(box(.875f, 3), box(.8125f, 1)), "R301");
        check("unknown".equals(p.label) && "conflicting_candidates".equals(p.reason)
                && p.classId == -1 && p.left == 0 && p.right == 0, "ambiguous classes expose no accepted box");
        p = opticDetector(rows(box(.99f, 4), box(.98f, 4)), "R301");
        check("four_x".equals(p.label), "same-class multiple rows do not create a class conflict");
        p = opticDetector(rows(box(.99f, 4), box(.81f, 1)), "R99");
        check("unknown".equals(p.label) && "unsupported_r99_state".equals(p.reason), "detector R99 restriction has no fallback");
        p = opticDetector(rows(box(.99f, 4), box(.98f, 1)), "R99");
        check("unknown".equals(p.label) && "conflicting_candidates".equals(p.reason), "detector conflict checked before R99 restriction");
        check("none".equals(opticDetector(rows(box(.99f, 0)), "R99").label), "detector none is class zero");
        check("one_x".equals(opticDetector(rows(box(.99f, 1)), "R99").label), "detector R99 one_x accepted");
        check("unknown".equals(opticDetector(rows(box(.99f, 1)), "unknown").label), "optic detector cannot invent gun");
        p = opticDetector(rows(box(Float.NaN, 0), box(1.01f, 1), box(.99f, 5), box(.99f, 1.5f)), "VK47");
        check("unknown".equals(p.label), "invalid detector score and class ids fail closed");
        float[] invalid = box(.99f, 1); invalid[3] = -4;
        check("unknown".equals(opticDetector(rows(invalid), "VK47").label), "inverted optic box rejected");
        rejects(() -> opticDetector(new float[299][6], "VK47"), "optic detector wrong row count rejected");
        rejects(() -> opticDetector(rows(new float[5]), "VK47"), "optic detector wrong row width rejected");
    }

    private static void masking() {
        int[] original = {0xff123456, 0x80123456, 0x00123456, 0xffffffff};
        int[] inferenceCopy = original.clone();
        byte[] mask = {1, 0, 0, 1};
        ImageTensor.applyMask(inferenceCopy, mask);
        check(Arrays.equals(inferenceCopy, new int[]{0xff123456, 0x80000000, 0x00000000, 0xffffffff}),
                "binary mask clears RGB and preserves alpha and kept pixels");
        check(Arrays.equals(original, new int[]{0xff123456, 0x80123456, 0x00123456, 0xffffffff}),
                "original source remains intact when applying mask to inference copy");
        check(Arrays.equals(mask, new byte[]{1, 0, 0, 1}), "mask is reusable and immutable during application");
        int[] invalidTarget = original.clone();
        rejects(() -> ImageTensor.applyMask(invalidTarget, new byte[]{0, 0, 0, 2}), "mask rejects nonbinary positive value");
        check(Arrays.equals(invalidTarget, original), "late invalid mask value causes no partial mutation");
        rejects(() -> ImageTensor.applyMask(invalidTarget, new byte[]{0, 0, 0, -1}), "mask rejects signed 255 byte");
        check(Arrays.equals(invalidTarget, original), "invalid unsigned byte causes no partial mutation");
        rejects(() -> ImageTensor.applyMask(invalidTarget, new byte[3]), "mask rejects wrong length");
        rejects(() -> ImageTensor.applyMask(invalidTarget, null), "mask rejects null mask");
        rejects(() -> ImageTensor.applyMask(null, new byte[4]), "mask rejects null pixels");
        rejects(() -> ImageTensor.applyMask(new int[0], new byte[0]), "empty mask rejected");
        int[] panel = new int[320 * 128]; Arrays.fill(panel, 0xffffffff);
        ImageTensor.applyMask(panel, new byte[panel.length]);
        float[] input = ImageTensor.detectorLetterbox(panel, 320, 128, new int[]{0,96,0,96});
        check(input[0] == 114/255.0f && input[96*320] == 0 && input[224*320] == 114/255.0f,
                "mask acts on panel before letterbox and never changes neutral padding");
    }

    private static void fixtures(File directory, File actualMask) throws Exception {
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IllegalStateException("Cannot create fixture folder");
        int[] pixels = new int[640 * 640];
        for (int y = 0; y < 640; y++) for (int x = 0; x < 640; x++) {
            int r = (x * 73 + y * 29 + (x * y) % 251) & 255;
            int g = (x * 11 + y * 191 + (x ^ y) * 17) & 255;
            int b = (x * 157 + y * 7 + (x * y) % 199) & 255;
            pixels[y * 640 + x] = 0xff000000 | (r << 16) | (g << 8) | b;
        }
        write(directory, "gun.f32", ImageTensor.detector(pixels));
        write(directory, "vk.f32", ImageTensor.optic(pixels, new int[]{263, 555, 299, 590}));
        write(directory, "r301.f32", ImageTensor.optic(pixels, new int[]{295, 555, 331, 590}));
        write(directory, "r99.f32", ImageTensor.optic(pixels, new int[]{285, 551, 330, 590}));
        int[] offsets = ImageTensor.horizontalOffsets(-40, 40, 4);
        write(directory, "locator-vk.f32", ImageTensor.opticCandidates(pixels, new int[]{263, 555, 299, 590}, offsets));
        write(directory, "locator-r301.f32", ImageTensor.opticCandidates(pixels, new int[]{295, 555, 331, 590}, offsets));
        write(directory, "locator-r99.f32", ImageTensor.opticCandidates(pixels, new int[]{285, 551, 330, 590}, offsets));
        int[] panel = panelPixels(pixels);
        write(directory, "panel-gun.f32", ImageTensor.detectorLetterbox(panel, 320, 128, new int[]{0,96,0,96}));
        write(directory, "panel-band.f32", ImageTensor.cropTensor(panel, 320, 128, new int[]{8,54,200,100}, 192, 64));
        byte[] keepMask = new byte[panel.length];
        for (int y = 0; y < 128; y++) for (int x = 0; x < 320; x++) {
            keepMask[y*320+x] = (byte) (((x/13+y/7)%3 == 0) ? 0 : 1);
        }
        ImageTensor.applyMask(panel, keepMask);
        write(directory, "panel-masked-gun.f32", ImageTensor.detectorLetterbox(panel, 320, 128, new int[]{0,96,0,96}));
        write(directory, "panel-masked-band.f32", ImageTensor.cropTensor(panel, 320, 128, new int[]{8,54,200,100}, 192, 64));
        if (actualMask != null) {
            int[] actualPanel = panelPixels(pixels);
            byte[] binary = Files.readAllBytes(actualMask.toPath());
            ImageTensor.applyMask(actualPanel, binary);
            write(directory, "actual-mask-panel-gun.f32", ImageTensor.detectorLetterbox(actualPanel, 320, 128, new int[]{0,96,0,96}));
            write(directory, "actual-mask-panel-band.f32", ImageTensor.cropTensor(actualPanel, 320, 128, new int[]{8,54,200,100}, 192, 64));
        }
        int[] downsized = ImageTensor.pillowBilinear(pixels, 640, 640, new int[]{7, 11, 80, 44}, 17, 64);
        try (DataOutputStream output = new DataOutputStream(new FileOutputStream(new File(directory, "pil-mixed.rgb")))) {
            for (int rgb : downsized) { output.writeByte(rgb >> 16); output.writeByte(rgb >> 8); output.writeByte(rgb); }
        }
    }

    private static void write(File directory, String name, float[] values) throws Exception {
        try (DataOutputStream output = new DataOutputStream(new FileOutputStream(new File(directory, name)))) {
            for (float value : values) output.writeFloat(value);
        }
    }
}
