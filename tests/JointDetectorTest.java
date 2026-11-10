import io.yingshu.framekey.ModelPostprocess;
import java.util.Arrays;

/** Joint output demultiplexing regressions; no Android, model files, or hardware required. */
public final class JointDetectorTest {
    private static int assertions;
    private static void check(boolean value, String detail) {
        assertions++;
        if (!value) throw new AssertionError(detail);
    }
    private static void rejects(Runnable action, String detail) {
        boolean rejected = false;
        try { action.run(); } catch (IllegalArgumentException expected) { rejected = true; }
        check(rejected, detail);
    }
    private static float[] box(float score, float globalClass) {
        return new float[]{71, 155, 107, 190, score, globalClass};
    }
    private static float[][] rows(float[]... detections) {
        float[][] rows = new float[300][6];
        for (int i = 0; i < detections.length; i++) rows[i] = detections[i];
        return rows;
    }
    private static ModelPostprocess.DetectionPrediction optic(float[][] rows, String gun) {
        return ModelPostprocess.jointOpticDetections(rows, gun, .8f, .8f, .1f);
    }
    private static void rejectedWithoutBox(ModelPostprocess.DetectionPrediction prediction, String reason) {
        check("unknown".equals(prediction.label) && reason.equals(prediction.reason)
                        && prediction.classId == -1 && prediction.left == 0 && prediction.top == 0
                        && prediction.right == 0 && prediction.bottom == 0,
                "rejected result has no usable box: " + reason);
    }

    public static void main(String[] args) {
        classSeparation();
        rejectionRules();
        invalidOutputs();
        nonMutationAndFullRows();
        System.out.println("JointDetectorTest: " + assertions + " assertions passed");
    }

    private static void classSeparation() {
        String[] guns = {"VK47", "R301", "R99", "NEMESIS", "ALTERNATOR", "LSTAR", "PROWLER", "CAR"};
        for (int gunId = 0; gunId < guns.length; gunId++) {
            float[][] gunOnly = rows(box(.99f, gunId));
            check(guns[gunId].equals(ModelPostprocess.gun(gunOnly, .25f).label), "gun identity unchanged " + gunId);
            rejectedWithoutBox(optic(gunOnly, guns[gunId]), "no_detection");
        }
        for (String gun : guns) check(ModelPostprocess.isKnownGun(gun), "known gun membership " + gun);
        check(!ModelPostprocess.isKnownGun(null) && !ModelPostprocess.isKnownGun("unknown") && !ModelPostprocess.isKnownGun("KRABER"), "unknown membership fails closed");
        String[] labels = {"none", "one_x", "two_x", "three_x", "four_x"};
        for (int localId = 0; localId < labels.length; localId++) {
            float[][] joint = rows(box(.99f, 0), box(.9f, localId + 8));
            ModelPostprocess.DetectionPrediction p = optic(joint, ModelPostprocess.gun(joint, .25f).label);
            check(labels[localId].equals(p.label) && p.classId == localId, "global class remapped " + (localId + 8));
            check(p.confidence == .9f, "gun confidence cannot replace optic score " + localId);
            check(p.left == 71 && p.top == 155 && p.right == 107 && p.bottom == 190,
                    "padded coordinates remain unchanged " + localId);
            float[][] opticOnly = rows(box(.99f, localId + 8));
            check("".equals(ModelPostprocess.gun(opticOnly, .25f).label), "optic cannot invent gun " + localId);
        }
        check("none".equals(optic(rows(box(.99f, 8)), "R99").label), "explicit empty optic slot remains none");
        check("one_x".equals(optic(rows(box(.99f, 9)), "R99").label), "R99 one_x supported");
        check("three_x".equals(ModelPostprocess.opticDetections(rows(box(.99f, 3)), "VK47", .8f, .8f, .1f).label),
                "legacy five-class decoder retains class 3 meaning");
        check("four_x".equals(optic(rows(box(.98f, 12)), "NEMESIS").label), "Nemesis 2-4 maps to four_x");
        for(String gun : new String[]{"ALTERNATOR", "LSTAR", "PROWLER", "CAR"}) {
            for(int localId=0;localId<labels.length;localId++)
                check(labels[localId].equals(optic(rows(box(.99f,localId+8)),gun).label), "new gun optic routing " + gun + "/" + labels[localId]);
        }
    }

    private static void rejectionRules() {
        rejectedWithoutBox(optic(rows(), "VK47"), "no_detection");
        rejectedWithoutBox(optic(rows(box(.799f, 8)), "VK47"), "no_detection");
        check("none".equals(optic(rows(box(.8f, 8)), "VK47").label), "optic threshold remains inclusive");
        rejectedWithoutBox(optic(rows(box(.99f, 8)), ""), "unsupported_gun");
        rejectedWithoutBox(optic(rows(box(.99f, 8)), "unknown"), "unsupported_gun");
        float[][] conflictGuns = rows(box(.9f, 1), box(.85f, 2), box(.99f, 8));
        String gun = ModelPostprocess.gun(conflictGuns, .25f).label;
        check("unknown".equals(gun), "joint output retains gun conflict rejection");
        rejectedWithoutBox(optic(conflictGuns, gun), "unsupported_gun");
        rejectedWithoutBox(optic(rows(box(.99f, 12), box(.81f, 9)), "R99"), "unsupported_r99_state");
        rejectedWithoutBox(optic(rows(box(.99f, 12), box(.98f, 9)), "R99"), "conflicting_candidates");
        rejectedWithoutBox(optic(rows(box(.875f, 11), box(.8125f, 9)), "R301"), "conflicting_candidates");
        check("four_x".equals(optic(rows(box(.99f, 12), box(.98f, 12)), "R301").label),
                "same optic class duplicates are not a conflict");
        check("four_x".equals(optic(rows(box(.99f, 1), box(.96f, 12), box(.8f, 10)), "R301").label),
                "gun rows neither compete with optics nor trigger optic conflicts");
    }

    private static void invalidOutputs() {
        for (float id : new float[]{-1, 2.5f, 3.5f, 7.5f, 13, Float.NaN, Float.POSITIVE_INFINITY, Float.MAX_VALUE})
            rejectedWithoutBox(optic(rows(box(.99f, id)), "VK47"), "no_detection");
        for (float score : new float[]{-.1f, 1.01f, Float.NaN, Float.NEGATIVE_INFINITY})
            rejectedWithoutBox(optic(rows(box(score, 8)), "VK47"), "no_detection");
        for (int coordinate = 0; coordinate < 4; coordinate++) {
            float[] corrupt = box(.99f, 8); corrupt[coordinate] = Float.NaN;
            rejectedWithoutBox(optic(rows(corrupt), "VK47"), "no_detection");
        }
        float[] inverted = box(.99f, 8); inverted[2] = inverted[0];
        rejectedWithoutBox(optic(rows(inverted), "VK47"), "no_detection");
        check("one_x".equals(optic(rows(box(.99f, Float.NaN), box(.9f, 9)), "VK47").label),
                "invalid rows are ignored while a separate valid detection survives");
        rejects(() -> optic(null, "VK47"), "null output rejected");
        rejects(() -> optic(new float[299][6], "VK47"), "truncated output rejected");
        rejects(() -> optic(new float[301][6], "VK47"), "unexpected extra output rows rejected");
        rejects(() -> optic(rows(new float[5]), "VK47"), "short row rejected");
        rejects(() -> optic(rows(new float[7]), "VK47"), "long row rejected");
        float[][] nullRow = rows(); nullRow[299] = null;
        rejects(() -> optic(nullRow, "VK47"), "null final row rejected");
        rejects(() -> ModelPostprocess.jointOpticDetections(rows(), "VK47", Float.NaN, .8f, .1f), "invalid threshold rejected");
    }

    private static void nonMutationAndFullRows() {
        float[][] joint = rows(box(.99f, 2));
        joint[299] = box(.9f, 9);
        float[][] before = new float[300][];
        for (int i = 0; i < joint.length; i++) before[i] = joint[i].clone();
        for (int repeat = 0; repeat < 2; repeat++) {
            String gun = ModelPostprocess.gun(joint, .25f).label;
            ModelPostprocess.DetectionPrediction p = optic(joint, gun);
            check("R99".equals(gun) && "one_x".equals(p.label), "last candidate remains visible on repeat " + repeat);
        }
        check(Arrays.deepEquals(joint, before), "both decoders leave global classes and all row values unchanged");
    }
}
