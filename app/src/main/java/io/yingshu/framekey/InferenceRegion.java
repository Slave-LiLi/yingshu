package io.yingshu.framekey;

/** Immutable frame/ROI geometry. Manifest coordinates must describe a real, contained rectangle. */
public final class InferenceRegion {
    public final int frameWidth, frameHeight, left, top, width, height;

    public InferenceRegion(int[] frameSize, int[] roiXywh) {
        if (frameSize == null || frameSize.length != 2 || roiXywh == null || roiXywh.length != 4) {
            throw new IllegalArgumentException("Expected frame_size[2] and roi_xywh[4]");
        }
        frameWidth = frameSize[0]; frameHeight = frameSize[1];
        left = roiXywh[0]; top = roiXywh[1]; width = roiXywh[2]; height = roiXywh[3];
        if (frameWidth <= 0 || frameHeight <= 0 || left < 0 || top < 0 || width <= 0 || height <= 0
                || left > frameWidth || top > frameHeight || width > frameWidth - left
                || height > frameHeight - top || (long) width * height > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("ROI must be contained in the declared frame");
        }
    }

    public int pixelCount() { return width * height; }
}
