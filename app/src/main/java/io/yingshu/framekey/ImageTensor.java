package io.yingshu.framekey;

import java.util.Arrays;

/** Pixel preprocessing independent of Android, with RGB /255 NCHW output. */
public final class ImageTensor {
    public static final int ROI_SIZE = 640;
    public static final int DETECTOR_SIZE = 320;
    public static final int OPTIC_SIZE = 64;
    public static final int LOCATOR_BATCH_SIZE = 21;
    private static final int PRECISION = 22;
    private ImageTensor() {}

    /** Mutates only the supplied inference copy. Validate the whole mask before changing any pixel. */
    public static void applyMask(int[] pixels, byte[] keepMask) {
        if (pixels == null) throw new IllegalArgumentException("Inference pixels are required");
        validateMask(keepMask, pixels.length);
        for (int i = 0; i < pixels.length; i++) {
            if (keepMask[i] == 0) pixels[i] &= 0xff000000;
        }
    }

    /** Binary row-major u8 mask: 1 keeps RGB, 0 clears RGB. No image decoding or file I/O. */
    public static void validateMask(byte[] keepMask, int pixelCount) {
        if (pixelCount <= 0 || keepMask == null || keepMask.length != pixelCount) {
            throw new IllegalArgumentException("Mask length must equal the nonempty inference ROI pixel count");
        }
        for (byte value : keepMask) {
            if (value != 0 && value != 1) throw new IllegalArgumentException("Mask values must be exactly 0 or 1");
        }
    }

    /** OpenCV INTER_LINEAR for exact 2:1 shrinking of an 8-bit RGB image. */
    public static float[] detector(int[] roi) {
        checkRoi(roi);
        final int size = DETECTOR_SIZE, plane = size * size;
        float[] result = new float[plane * 3];
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                int p = y * 2 * ROI_SIZE + x * 2;
                int a = roi[p], b = roi[p + 1], c = roi[p + ROI_SIZE], d = roi[p + ROI_SIZE + 1];
                int out = y * size + x;
                for (int channel = 0; channel < 3; channel++) {
                    int shift = 16 - channel * 8;
                    int sum = ((a >> shift) & 255) + ((b >> shift) & 255)
                            + ((c >> shift) & 255) + ((d >> shift) & 255);
                    result[channel * plane + out] = ((sum + 2) >> 2) / 255.0f;
                }
            }
        }
        return result;
    }

    /** Letterbox without resampling. Padding order: left, top, right, bottom; RGB fill is 114. */
    public static float[] detectorLetterbox(int[] roi, int width, int height, int[] padding) {
        checkPixels(roi, width, height);
        validateLetterbox(width, height, padding);
        int plane = DETECTOR_SIZE * DETECTOR_SIZE;
        float[] result = new float[plane * 3];
        Arrays.fill(result, 114 / 255.0f);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int rgb = roi[y * width + x];
                int target = (y + padding[1]) * DETECTOR_SIZE + x + padding[0];
                result[target] = ((rgb >> 16) & 255) / 255.0f;
                result[plane + target] = ((rgb >> 8) & 255) / 255.0f;
                result[plane * 2 + target] = (rgb & 255) / 255.0f;
            }
        }
        return result;
    }

    public static void validateLetterbox(int width, int height, int[] padding) {
        if (width <= 0 || height <= 0 || padding == null || padding.length != 4) {
            throw new IllegalArgumentException("Invalid letterbox dimensions");
        }
        for (int value : padding) if (value < 0) throw new IllegalArgumentException("Negative letterbox padding");
        if ((long) width + padding[0] + padding[2] != DETECTOR_SIZE
                || (long) height + padding[1] + padding[3] != DETECTOR_SIZE) {
            throw new IllegalArgumentException("Unscaled ROI plus padding must form a 320x320 tensor");
        }
    }

    /** PIL Image.convert('RGB').crop(xyxy).resize((64,64), BILINEAR). */
    public static float[] optic(int[] roi, int[] crop) {
        return optic(roi, ROI_SIZE, ROI_SIZE, crop);
    }

    public static float[] optic(int[] roi, int width, int height, int[] crop) {
        return cropTensor(roi, width, height, crop, OPTIC_SIZE, OPTIC_SIZE);
    }

    /** Arbitrary rectangular RGB crop and PIL BILINEAR output, packed as one NCHW image. */
    public static float[] cropTensor(int[] roi, int width, int height, int[] crop, int outputWidth, int outputHeight) {
        checkPixels(roi, width, height);
        validateCrop(crop, width, height);
        int[] rgb = pillowBilinear(roi, width, height, crop, outputWidth, outputHeight);
        int plane = outputWidth * outputHeight;
        float[] result = new float[plane * 3];
        for (int i = 0; i < plane; i++) {
            result[i] = ((rgb[i] >> 16) & 255) / 255.0f;
            result[plane + i] = ((rgb[i] >> 8) & 255) / 255.0f;
            result[plane * 2 + i] = (rgb[i] & 255) / 255.0f;
        }
        return result;
    }

    /** One NCHW image per horizontal candidate, in the supplied offset order. */
    public static float[] opticCandidates(int[] roi, int[] nominalCrop, int[] offsets) {
        return opticCandidates(roi, ROI_SIZE, ROI_SIZE, nominalCrop, offsets);
    }

    public static float[] opticCandidates(int[] roi, int width, int height, int[] nominalCrop, int[] offsets) {
        checkPixels(roi, width, height);
        validateCandidateCrops(nominalCrop, width, height, offsets);
        int imageSize = 3 * OPTIC_SIZE * OPTIC_SIZE;
        float[] batch = new float[offsets.length * imageSize];
        for (int i = 0; i < offsets.length; i++) {
            int[] crop = shiftedCrop(nominalCrop, offsets[i]);
            float[] candidate = optic(roi, width, height, crop);
            System.arraycopy(candidate, 0, batch, i * imageSize, imageSize);
        }
        return batch;
    }

    public static int[] horizontalOffsets(int minimum, int maximum, int step) {
        if (step <= 0 || minimum > 0 || maximum < 0 || maximum <= minimum
                || (maximum - minimum) % step != 0 || -minimum % step != 0
                || (long) maximum - minimum > 2L * ROI_SIZE) {
            throw new IllegalArgumentException("Invalid horizontal search offsets");
        }
        int[] offsets = new int[(maximum - minimum) / step + 1];
        for (int i = 0; i < offsets.length; i++) offsets[i] = minimum + i * step;
        return offsets;
    }

    public static void validateCandidateCrops(int[] nominalCrop, int[] offsets) {
        validateCandidateCrops(nominalCrop, ROI_SIZE, ROI_SIZE, offsets);
    }

    public static void validateCandidateCrops(int[] nominalCrop, int width, int height, int[] offsets) {
        validateCrop(nominalCrop, width, height);
        if (offsets == null || offsets.length != LOCATOR_BATCH_SIZE) {
            throw new IllegalArgumentException("Locator requires 21 horizontal candidates");
        }
        for (int i = 0; i < offsets.length; i++) {
            if (offsets[i] != -40 + 4 * i) throw new IllegalArgumentException("Locator offsets must be -40..40, step 4");
            validateCrop(shiftedCrop(nominalCrop, offsets[i]), width, height);
        }
    }

    private static int[] shiftedCrop(int[] crop, int offset) {
        return new int[]{crop[0] + offset, crop[1], crop[2] + offset, crop[3]};
    }

    public static void validateCrop(int[] crop) {
        validateCrop(crop, ROI_SIZE, ROI_SIZE);
    }

    public static void validateCrop(int[] crop, int width, int height) {
        if (width <= 0 || height <= 0 || crop == null || crop.length != 4 || crop[0] < 0 || crop[1] < 0
                || crop[2] > width || crop[3] > height
                || crop[2] <= crop[0] || crop[3] <= crop[1]) {
            throw new IllegalArgumentException("Crop must be contained in the ROI");
        }
    }

    /** PIL's separable, normalized 22-bit coefficient filter, including 8-bit rounding per pass. */
    public static int[] pillowBilinear(int[] pixels, int width, int height, int[] crop, int outW, int outH) {
        if (pixels == null || width <= 0 || height <= 0 || pixels.length != width * height
                || crop == null || crop.length != 4 || crop[0] < 0 || crop[1] < 0
                || crop[2] > width || crop[3] > height || crop[2] <= crop[0] || crop[3] <= crop[1]
                || outW <= 0 || outH <= 0) {
            throw new IllegalArgumentException("Invalid image/crop dimensions");
        }
        int cropW = crop[2] - crop[0], cropH = crop[3] - crop[1];
        Weights horizontal = new Weights(cropW, outW);
        Weights vertical = new Weights(cropH, outH);
        int[] temporary = new int[outW * cropH];
        for (int y = 0; y < cropH; y++) {
            for (int x = 0; x < outW; x++) {
                int start = (y + crop[1]) * width + crop[0] + horizontal.start[x];
                temporary[y * outW + x] = filter(pixels, start, 1, horizontal.values[x]);
            }
        }
        int[] output = new int[outW * outH];
        for (int y = 0; y < outH; y++) {
            for (int x = 0; x < outW; x++) {
                output[y * outW + x] = filter(temporary, vertical.start[y] * outW + x, outW, vertical.values[y]);
            }
        }
        return output;
    }

    private static int filter(int[] pixels, int start, int stride, int[] coefficients) {
        int rgb = 0xff000000;
        for (int shift = 16; shift >= 0; shift -= 8) {
            int accumulator = 1 << (PRECISION - 1);
            for (int i = 0; i < coefficients.length; i++) {
                accumulator += ((pixels[start + i * stride] >> shift) & 255) * coefficients[i];
            }
            int value = Math.min(255, Math.max(0, accumulator >> PRECISION));
            rgb |= value << shift;
        }
        return rgb;
    }

    private static final class Weights {
        final int[] start;
        final int[][] values;
        Weights(int inputSize, int outputSize) {
            start = new int[outputSize];
            values = new int[outputSize][];
            double scale = (double) inputSize / outputSize;
            double filterScale = Math.max(1.0, scale);
            for (int out = 0; out < outputSize; out++) {
                double center = (out + 0.5) * scale;
                int first = Math.max(0, (int) (center - filterScale + 0.5));
                int end = Math.min(inputSize, (int) (center + filterScale + 0.5));
                start[out] = first;
                double[] raw = new double[end - first];
                double sum = 0;
                for (int i = 0; i < raw.length; i++) {
                    raw[i] = Math.max(0.0, 1.0 - Math.abs((first + i + 0.5 - center) / filterScale));
                    sum += raw[i];
                }
                values[out] = new int[raw.length];
                for (int i = 0; i < raw.length; i++) {
                    values[out][i] = (int) (raw[i] / sum * (1 << PRECISION) + 0.5);
                }
            }
        }
    }

    private static void checkRoi(int[] roi) {
        checkPixels(roi, ROI_SIZE, ROI_SIZE);
    }

    private static void checkPixels(int[] pixels, int width, int height) {
        if (pixels == null || width <= 0 || height <= 0 || pixels.length != (long) width * height) {
            throw new IllegalArgumentException("Pixel array must match the declared ROI dimensions");
        }
    }
}
