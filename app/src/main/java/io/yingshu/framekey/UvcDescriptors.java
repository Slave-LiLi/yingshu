package io.yingshu.framekey;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Small, bounds-checked reader for UVC MJPEG format/frame descriptors. */
final class UvcDescriptors {
    static final class Mode {
        final int interfaceId, formatIndex, frameIndex, width, height;
        final long maxFrameBytes, defaultInterval;
        final long[] intervals;
        final long minInterval, maxInterval, stepInterval;

        Mode(int interfaceId, int formatIndex, int frameIndex, int width, int height,
                long maxFrameBytes, long defaultInterval, long[] intervals,
                long minInterval, long maxInterval, long stepInterval) {
            this.interfaceId = interfaceId;
            this.formatIndex = formatIndex;
            this.frameIndex = frameIndex;
            this.width = width;
            this.height = height;
            this.maxFrameBytes = maxFrameBytes;
            this.defaultInterval = defaultInterval;
            this.intervals = intervals;
            this.minInterval = minInterval;
            this.maxInterval = maxInterval;
            this.stepInterval = stepInterval;
        }

        long chooseInterval(boolean eco) {
            long wanted = eco ? 1_000_000L : 333_333L;
            if (intervals.length > 0) {
                long closest = intervals[0];
                long slower = Long.MAX_VALUE;
                for (long value : intervals) {
                    if (Math.abs(value - wanted) < Math.abs(closest - wanted)) closest = value;
                    if (value >= wanted && value < slower) slower = value;
                }
                return eco && slower != Long.MAX_VALUE ? slower : closest;
            }
            if (minInterval > 0 && maxInterval >= minInterval) {
                long value = Math.max(minInterval, Math.min(maxInterval, wanted));
                if (stepInterval > 0) {
                    long steps = (value - minInterval + stepInterval - 1) / stepInterval;
                    value = Math.min(maxInterval, minInterval + steps * stepInterval);
                }
                return value;
            }
            return defaultInterval > 0 ? defaultInterval : wanted;
        }

        @Override public String toString() {
            return String.format(Locale.ROOT, "MJPEG %d×%d (if=%d format=%d frame=%d)",
                    width, height, interfaceId, formatIndex, frameIndex);
        }
    }

    final List<Mode> modes;
    final int version;
    final boolean malformed;

    private UvcDescriptors(List<Mode> modes, int version, boolean malformed) {
        this.modes = Collections.unmodifiableList(modes);
        this.version = version;
        this.malformed = malformed;
    }

    static UvcDescriptors parse(byte[] bytes) {
        List<Mode> modes = new ArrayList<>();
        int version = 0x0100, intf = -1, interfaceClass = -1, sub = -1, format = -1;
        boolean malformed = false;
        if (bytes == null) return new UvcDescriptors(modes, version, true);
        int p = 0;
        while (p + 2 <= bytes.length) {
            int n = u8(bytes, p);
            if (n < 2 || p + n > bytes.length) { malformed = true; break; }
            int type = u8(bytes, p + 1);
            if (type == 4) {
                intf = -1; interfaceClass = -1; sub = -1; format = -1;
                if (n >= 9) {
                    intf = u8(bytes, p + 2);
                    interfaceClass = u8(bytes, p + 5);
                    sub = u8(bytes, p + 6);
                }
            } else if (type == 0x24 && interfaceClass == 14 && n >= 3) {
                int subtype = u8(bytes, p + 2);
                if (sub == 1 && subtype == 1 && n >= 5) version = u16(bytes, p + 3);
                if (sub == 2) {
                    if (subtype == 6 && n >= 11) {
                        format = u8(bytes, p + 3);
                    } else if (subtype == 4 || subtype == 0x10 || subtype == 0x13
                            || subtype == 0x15 || subtype == 0x16 || subtype == 0x0a) {
                        format = -1;
                    } else if (subtype == 7 && format > 0 && n >= 26) {
                        int width = u16(bytes, p + 5), height = u16(bytes, p + 7);
                        int index = u8(bytes, p + 3);
                        int count = u8(bytes, p + 25);
                        List<Long> values = new ArrayList<>();
                        long min = 0, max = 0, step = 0;
                        if (count == 0) {
                            if (n >= 38) {
                                min = u32(bytes, p + 26); max = u32(bytes, p + 30); step = u32(bytes, p + 34);
                            } else malformed = true;
                        } else {
                            int available = Math.min(count, (n - 26) / 4);
                            if (available != count) malformed = true;
                            for (int i = 0; i < available; i++) {
                                long value = u32(bytes, p + 26 + 4 * i);
                                if (value > 0) values.add(value);
                            }
                        }
                        if (index > 0 && width > 0 && height > 0 && (long) width * height <= 8_294_400L) {
                            long[] intervals = new long[values.size()];
                            for (int i = 0; i < values.size(); i++) intervals[i] = values.get(i);
                            modes.add(new Mode(intf, format, index, width, height, u32(bytes, p + 17),
                                    u32(bytes, p + 21), intervals, min, max, step));
                        }
                    }
                }
            }
            p += n;
        }
        if (p != bytes.length) malformed = true;
        return new UvcDescriptors(modes, version, malformed);
    }

    List<Mode> preferredModes(int interfaceId) {
        List<Mode> result = new ArrayList<>();
        // This product requires full-HD capture. Lower modes are retained in the
        // catalog for validation, but are never offered as silent fallbacks.
        for (Mode mode : modes) {
            if (mode.interfaceId == interfaceId && isFullHd(mode.width, mode.height)) result.add(mode);
        }
        return result;
    }

    Mode find(int interfaceId, int formatIndex, int frameIndex) {
        for (Mode mode : modes) {
            if (mode.interfaceId == interfaceId && mode.formatIndex == formatIndex && mode.frameIndex == frameIndex) return mode;
        }
        return null;
    }

    static boolean isFullHd(int width, int height) { return width == 1920 && height == 1080; }

    static int u8(byte[] b, int p) { return b[p] & 255; }
    static int u16(byte[] b, int p) { return u8(b, p) | (u8(b, p + 1) << 8); }
    static long u32(byte[] b, int p) {
        return u8(b, p) | ((long) u8(b, p + 1) << 8) | ((long) u8(b, p + 2) << 16) | ((long) u8(b, p + 3) << 24);
    }
    static void put32(byte[] b, int p, long value) {
        for (int i = 0; i < 4; i++) b[p + i] = (byte) (value >>> (8 * i));
    }
}
