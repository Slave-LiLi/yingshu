package io.yingshu.framekey;

/** Stateless prediction decoding. No frame history, OCR or output actions. */
public final class ModelPostprocess {
    private static final String[] GUNS = {"VK47", "R301", "R99", "NEMESIS", "ALTERNATOR", "LSTAR", "PROWLER", "CAR"};
    private static final String[] OPTICS = {"none", "one_x", "two_x", "three_x", "four_x", "background"};
    private ModelPostprocess() {}
    public static boolean isKnownGun(String gun) {
        for (String known : GUNS) if (known.equals(gun)) return true;
        return false;
    }
    public static final class Prediction {
        public final String label;
        public final float confidence;
        Prediction(String label, float confidence) { this.label = label; this.confidence = confidence; }
    }

    public static final class LocatorPrediction {
        public final String label, reason;
        /** Best non-background class probability, retained for diagnostics even when rejected. */
        public final float confidence;
        /** 1 - background probability at the best candidate; not a calibrated geometric probability. */
        public final float locatorConfidence;
        /** Valid accepted location only. Unknown returns offset 0 and candidateIndex -1. */
        public final int offsetX, candidateIndex;
        LocatorPrediction(String label, float confidence, float locatorConfidence,
                          int offsetX, int candidateIndex, String reason) {
            this.label = label; this.confidence = confidence; this.locatorConfidence = locatorConfidence;
            this.offsetX = offsetX; this.candidateIndex = candidateIndex; this.reason = reason;
        }
        public static LocatorPrediction unknown(String reason) {
            return new LocatorPrediction("unknown", 0, 0, 0, -1, reason);
        }
    }

    /** Five-class detector result. Box coordinates refer to the padded 320x320 model input. */
    public static final class DetectionPrediction {
        public final String label, reason;
        public final float confidence, left, top, right, bottom;
        /** Unknown has no accepted class or box. Its confidence retains the best valid score. */
        public final int classId;
        private DetectionPrediction(String label, float confidence, int classId, float[] box, String reason) {
            this.label = label; this.confidence = confidence; this.classId = classId; this.reason = reason;
            left = box == null ? 0 : box[0]; top = box == null ? 0 : box[1];
            right = box == null ? 0 : box[2]; bottom = box == null ? 0 : box[3];
        }
        public static DetectionPrediction unknown(String reason) {
            return new DetectionPrediction("unknown", 0, -1, null, reason);
        }
    }

    /** Decode exactly 300 end-to-end xyxy/score/class rows. Invalid rows never become detections. */
    public static Prediction gun(float[][] rows, float threshold) {
        if (rows == null || rows.length != 300 || !finite(threshold) || threshold < 0 || threshold > 1) {
            throw new IllegalArgumentException("Gun output must have shape [1,300,6]");
        }
        float[] best = new float[GUNS.length];
        for (float[] row : rows) {
            if (row == null || row.length != 6) throw new IllegalArgumentException("Gun row must have six values");
            boolean valid = true;
            for (float value : row) if (!finite(value)) valid = false;
            int id = (int) row[5];
            if (!valid || id < 0 || id >= GUNS.length || row[5] != id
                    || row[4] < threshold || row[4] > 1 || row[2] <= row[0] || row[3] <= row[1]) continue;
            best[id] = Math.max(best[id], row[4]);
        }
        int winner = -1;
        float score = 0, runnerUp = 0;
        for (int i = 0; i < best.length; i++) {
            if (best[i] > score) { runnerUp = score; score = best[i]; winner = i; }
            else runnerUp = Math.max(runnerUp, best[i]);
        }
        if (winner < 0) return new Prediction("", 0);
        // Two different high-scoring weapons are unsafe to assign to a key rule.
        if (runnerUp >= 0.5f && score - runnerUp <= 0.10f) return new Prediction("unknown", score);
        return new Prediction(GUNS[winner], score);
    }

    public static Prediction optic(float[] logits, String[] labels, float threshold) {
        if (logits == null || labels == null || logits.length != labels.length || logits.length < 2
                || !finite(threshold) || threshold <= 0 || threshold > 1) {
            throw new IllegalArgumentException("Invalid optic logits/classes/threshold");
        }
        int winner = 0;
        double maximum = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < logits.length; i++) {
            if (!finite(logits[i])) return new Prediction("unknown", 0);
            if (logits[i] > maximum) { maximum = logits[i]; winner = i; }
        }
        double sum = 0;
        for (float logit : logits) sum += Math.exp(logit - maximum);
        float probability = (float) (1.0 / sum);
        return new Prediction(probability >= threshold ? labels[winner] : "unknown", probability);
    }

    /** Five-class xyxy/score/class decoder; scores are detector probabilities, not softmax logits. */
    public static DetectionPrediction opticDetections(float[][] rows, String gun, float scoreThreshold,
                                                       float conflictThreshold, float conflictMargin) {
        return opticDetections(rows, gun, scoreThreshold, conflictThreshold, conflictMargin, 0);
    }

    /**
     * Shared detector classes are VK47,R301,R99,NEMESIS,ALTERNATOR,LSTAR,PROWLER,CAR,none,one_x,two_x,three_x,four_x.
     * Only global classes 8..12 participate in optic selection; returned classId remains 0..4.
     * Input rows and padded model-input box coordinates are preserved.
     */
    public static DetectionPrediction jointOpticDetections(float[][] rows, String gun, float scoreThreshold,
                                                            float conflictThreshold, float conflictMargin) {
        return opticDetections(rows, gun, scoreThreshold, conflictThreshold, conflictMargin, 8);
    }

    private static DetectionPrediction opticDetections(float[][] rows, String gun, float scoreThreshold,
                                                        float conflictThreshold, float conflictMargin, int classOffset) {
        if (rows == null || rows.length != 300 || !probability(scoreThreshold)
                || !probability(conflictThreshold) || !probability(conflictMargin)) {
            throw new IllegalArgumentException("Optic detector requires [1,300,6] and valid thresholds");
        }
        float[] best = new float[5];
        float[][] boxes = new float[5][];
        for (float[] row : rows) {
            if (row == null || row.length != 6) throw new IllegalArgumentException("Optic detection row must have six values");
            boolean valid = true;
            for (float value : row) if (!finite(value)) valid = false;
            int sourceId = (int) row[5];
            if (!valid || sourceId < classOffset || sourceId >= classOffset + 5 || row[5] != sourceId
                    || row[4] < scoreThreshold || row[4] > 1
                    || row[2] <= row[0] || row[3] <= row[1]) continue;
            int id = sourceId - classOffset;
            if (row[4] > best[id]) { best[id] = row[4]; boxes[id] = row; }
        }
        int winner = -1;
        float score = 0, runnerUp = 0;
        for (int id = 0; id < 5; id++) {
            if (best[id] > score) { runnerUp = score; score = best[id]; winner = id; }
            else runnerUp = Math.max(runnerUp, best[id]);
        }
        String reason = null;
        if (!isKnownGun(gun)) reason = "unsupported_gun";
        else if (winner < 0) reason = "no_detection";
        else if (runnerUp >= conflictThreshold && score - runnerUp <= conflictMargin) reason = "conflicting_candidates";
        else if ("R99".equals(gun) && winner > 1) reason = "unsupported_r99_state";
        if (reason != null) return new DetectionPrediction("unknown", score, -1, null, reason);
        return new DetectionPrediction(OPTICS[winner], score, winner, boxes[winner], "accepted");
    }

    /**
     * Shared six-class search. Select globally before applying the R99 two-state restriction;
     * never fall back to a weaker allowed state or pick a nearby slot after a conflict.
     */
    public static LocatorPrediction locate(float[][] logits, String gun, int[] offsets,
                                           float threshold, float minMargin,
                                           float conflictThreshold, float conflictMargin) {
        if (logits == null || logits.length != ImageTensor.LOCATOR_BATCH_SIZE
                || offsets == null || offsets.length != logits.length
                || !probability(threshold) || !probability(minMargin)
                || !probability(conflictThreshold) || !probability(conflictMargin)) {
            throw new IllegalArgumentException("Invalid locator shape, offsets or thresholds");
        }
        for (int i = 0; i < offsets.length; i++) {
            if (offsets[i] != -40 + 4 * i) throw new IllegalArgumentException("Locator offsets must be -40..40, step 4");
            if (logits[i] == null || logits[i].length != OPTICS.length) {
                throw new IllegalArgumentException("Locator output must have shape [21,6]");
            }
        }
        if (!isKnownGun(gun)) {
            return LocatorPrediction.unknown("unsupported_gun");
        }
        float[][] probabilities = new float[logits.length][OPTICS.length];
        int bestCandidate = -1, bestClass = -1;
        float bestProbability = -1;
        for (int candidate = 0; candidate < logits.length; candidate++) {
            double maximum = Double.NEGATIVE_INFINITY;
            for (float value : logits[candidate]) {
                if (!finite(value)) return LocatorPrediction.unknown("nonfinite_or_invalid_output");
                maximum = Math.max(maximum, value);
            }
            double sum = 0;
            double[] exponentials = new double[OPTICS.length];
            for (int id = 0; id < OPTICS.length; id++) {
                exponentials[id] = Math.exp((double) logits[candidate][id] - maximum);
                sum += exponentials[id];
            }
            for (int id = 0; id < OPTICS.length; id++) {
                float p = probabilities[candidate][id] = (float) (exponentials[id] / sum);
                if (id == 5) continue;
                if (p > bestProbability || (p == bestProbability && preferable(candidate, id,
                        bestCandidate, bestClass, offsets))) {
                    bestProbability = p; bestCandidate = candidate; bestClass = id;
                }
            }
        }
        float background = probabilities[bestCandidate][5];
        float locationConfidence = 1.0f - background;
        String reason = null;
        if (bestProbability < threshold) reason = "low_probability";
        else if (bestProbability - background < minMargin) reason = "background_margin";
        if (reason == null) {
            for (int candidate = 0; candidate < logits.length && reason == null; candidate++) {
                for (int id = 0; id < 5; id++) {
                    if (id != bestClass && probabilities[candidate][id] >= conflictThreshold
                            && bestProbability - probabilities[candidate][id] <= conflictMargin) {
                        reason = "conflicting_candidates";
                        break;
                    }
                }
            }
        }
        if (reason == null && "R99".equals(gun) && bestClass > 1) reason = "unsupported_r99_state";
        if (reason != null) return new LocatorPrediction("unknown", bestProbability,
                locationConfidence, 0, -1, reason);
        return new LocatorPrediction(OPTICS[bestClass], bestProbability, locationConfidence,
                offsets[bestCandidate], bestCandidate, "accepted");
    }

    private static boolean preferable(int candidate, int id, int bestCandidate, int bestClass, int[] offsets) {
        if (bestCandidate < 0) return true;
        int distance = Math.abs(offsets[candidate]), bestDistance = Math.abs(offsets[bestCandidate]);
        if (distance != bestDistance) return distance < bestDistance;
        if (offsets[candidate] != offsets[bestCandidate]) return offsets[candidate] < offsets[bestCandidate];
        return id < bestClass;
    }

    private static boolean probability(float value) { return finite(value) && value > 0 && value <= 1; }

    private static boolean finite(float value) { return !Float.isNaN(value) && !Float.isInfinite(value); }
}
