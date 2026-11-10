#ifndef YINGSHU_NDI_PIXELS_H
#define YINGSHU_NDI_PIXELS_H
#include <stdint.h>
#include <stddef.h>

// A deliberately small RGB conversion surface, shared by JNI and the native test.
// NDI alpha is discarded: model RGB must not be changed by Android's premultiplication.
struct NdiRgbSource {
    const uint8_t* pixels;
    int width, height, stride;
    bool blueFirst;
};
struct NdiRgbRegion { int left, top, width, height; };

static inline bool ndiRgbValid(const NdiRgbSource& source, const NdiRgbRegion& region,
                              int outWidth, int outHeight, int outStride) {
    return source.pixels && source.width > 0 && source.height > 0 &&
           source.width <= 16384 && source.height <= 16384 &&
           source.stride >= source.width * 4 &&
           region.left >= 0 && region.top >= 0 && region.width > 0 && region.height > 0 &&
           region.left <= source.width - region.width && region.top <= source.height - region.height &&
           outWidth > 0 && outHeight > 0 && outWidth <= 16384 && outHeight <= 16384 &&
           outStride >= outWidth * 4;
}

static inline bool ndiCopyRgb(const NdiRgbSource& source, const NdiRgbRegion& region,
                             uint8_t* output, int outWidth, int outHeight, int outStride) {
    if (!output || !ndiRgbValid(source, region, outWidth, outHeight, outStride)) return false;
    // The normal paths are a 1:1 model crop and a 2:1 preview. Their source
    // coordinates have constant integer steps; avoid division for every pixel.
    // Byte reads/writes retain support for unaligned buffers and padded strides.
    if (region.width % outWidth == 0 && region.height % outHeight == 0) {
        const size_t pixelStep = static_cast<size_t>(region.width / outWidth) * 4;
        const size_t rowStep = static_cast<size_t>(region.height / outHeight) * source.stride;
        const uint8_t* row = source.pixels + static_cast<size_t>(region.top) * source.stride
                            + static_cast<size_t>(region.left) * 4;
        const int red = source.blueFirst ? 2 : 0, blue = source.blueFirst ? 0 : 2;
        for (int y = 0; y < outHeight; ++y) {
            const uint8_t* pixel = row;
            uint8_t* destination = output + static_cast<size_t>(y) * outStride;
            for (int x = 0; x < outWidth; ++x) {
                destination[0] = pixel[red];
                destination[1] = pixel[1];
                destination[2] = pixel[blue];
                destination[3] = 255;
                pixel += pixelStep; destination += 4;
            }
            // Avoid constructing a pointer beyond the source object after the
            // final row when a cropped region starts partway across the image.
            if (y + 1 < outHeight) row += rowStep;
        }
        return true;
    }
    for (int y = 0; y < outHeight; ++y) {
        int sy = region.top + static_cast<int>(static_cast<int64_t>(y) * region.height / outHeight);
        const uint8_t* row = source.pixels + static_cast<size_t>(sy) * source.stride;
        uint8_t* destination = output + static_cast<size_t>(y) * outStride;
        for (int x = 0; x < outWidth; ++x) {
            int sx = region.left + static_cast<int>(static_cast<int64_t>(x) * region.width / outWidth);
            const uint8_t* pixel = row + sx * 4;
            destination[x * 4] = pixel[source.blueFirst ? 2 : 0];
            destination[x * 4 + 1] = pixel[1];
            destination[x * 4 + 2] = pixel[source.blueFirst ? 0 : 2];
            destination[x * 4 + 3] = 255;
        }
    }
    return true;
}

static inline bool ndiTrialPanel(int width, int height, NdiRgbRegion* panel) {
    if (width != 1920 || height != 1080 || !panel) return false;
    *panel = {1472, 936, 320, 128};
    return true;
}

static inline void ndiPreviewSize(int width, int height, int* outWidth, int* outHeight) {
    int longest = width > height ? width : height;
    *outWidth = longest > 960 ? width * 960 / longest : width;
    *outHeight = longest > 960 ? height * 960 / longest : height;
    if (*outWidth < 1) *outWidth = 1;
    if (*outHeight < 1) *outHeight = 1;
}
#endif
