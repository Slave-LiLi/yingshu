package io.yingshu.framekey;

import java.util.Arrays;

/**
 * Incremental USB bulk UVC payload reader. A full Java read is not necessarily a
 * UVC payload: only a short transfer or dwMaxPayloadTransferSize ends a payload.
 * Protocol facts: USB-IF UVC 1.1/1.5 section 2.4.3 and MJPEG payload specification.
 */
final class UvcPayloadAssembler {
    interface Listener { void onJpeg(byte[] jpeg); }
    private final Listener listener;
    private final int maxPayloadBytes, maxFrameBytes;
    private byte[] frame;
    private final byte[] header = new byte[255];
    private int payloadBytes, headerBytes, headerLength = -1, headerFlags;
    private boolean skipPayload;
    private int frameBytes, frameFid = -1;
    private boolean frameBroken;
    long frames, droppedFrames, invalidPayloads, errorPayloads;

    UvcPayloadAssembler(int maxPayloadBytes, int maxFrameBytes, Listener listener) {
        if (maxPayloadBytes < 2 || maxPayloadBytes > 16 * 1024 * 1024) throw new IllegalArgumentException("payload size");
        if (maxFrameBytes < 4 || maxFrameBytes > 16 * 1024 * 1024) throw new IllegalArgumentException("frame size");
        this.maxPayloadBytes = maxPayloadBytes;
        this.maxFrameBytes = maxFrameBytes;
        this.listener = listener;
        frame = new byte[Math.min(maxFrameBytes, 256 * 1024)];
    }

    /** requestedLength is the actual bulkTransfer request length, not buffer capacity. */
    void accept(byte[] data, int length, int requestedLength) {
        if (length < 0 || requestedLength <= 0 || length > requestedLength || data == null || length > data.length) {
            throw new IllegalArgumentException("invalid transfer bounds");
        }
        int offset = 0;
        while (offset < length) {
            int count = Math.min(length - offset, maxPayloadBytes - payloadBytes);
            process(data, offset, count);
            payloadBytes += count;
            offset += count;
            if (payloadBytes == maxPayloadBytes) endPayload();
        }
        if (length < requestedLength && payloadBytes != 0) endPayload();
    }

    /** Use after a failed/incomplete USB transfer; wait for a fresh FID to recover. */
    void discontinuity() {
        if (frameBytes > 0) droppedFrames++;
        frameBytes = 0;
        frameBroken = true;
        resetPayload();
    }

    private void process(byte[] data, int p, int length) {
        int end = p + length;
        if (skipPayload) return;
        while (p < end && (headerLength < 0 || headerBytes < headerLength)) {
            if (headerBytes >= header.length) { invalidate(); return; }
            header[headerBytes++] = data[p++];
            if (headerBytes == 1) {
                headerLength = header[0] & 255;
                if (headerLength < 2 || headerLength > maxPayloadBytes) { invalidate(); return; }
            }
            if (headerBytes == 2) {
                headerFlags = header[1] & 255;
                int minimum = 2 + ((headerFlags & 4) != 0 ? 4 : 0) + ((headerFlags & 8) != 0 ? 6 : 0);
                if (headerLength < minimum) { invalidate(); return; }
            }
            if (headerBytes == headerLength) beginFramePayload();
            if (skipPayload) return;
        }
        if (p < end && !frameBroken) append(data, p, end - p);
    }

    private void beginFramePayload() {
        int fid = headerFlags & 1;
        if (frameFid != fid) {
            if (frameBytes > 0) finishFrame();
            frameFid = fid;
            frameBytes = 0;
            frameBroken = false;
        }
        if ((headerFlags & 0x40) != 0) {
            errorPayloads++;
            frameBroken = true;
            skipPayload = true;
        }
    }

    private void append(byte[] data, int offset, int length) {
        if (length > maxFrameBytes - frameBytes) {
            frameBroken = true;
            return;
        }
        int required = frameBytes + length;
        if (required > frame.length) frame = Arrays.copyOf(frame, Math.min(maxFrameBytes, Math.max(required, frame.length * 2)));
        System.arraycopy(data, offset, frame, frameBytes, length);
        frameBytes += length;
    }

    private void endPayload() {
        if (!skipPayload && (headerLength < 2 || headerBytes != headerLength)) invalidate();
        if ((headerFlags & 2) != 0 && headerBytes >= 2) {
            finishFrame();
            // FID remains known. Well-formed next frame toggles it. Also allow
            // devices that delimit by EOF alone, without joining two frames.
            frameBytes = 0;
            frameBroken = false;
        }
        resetPayload();
    }

    private void finishFrame() {
        int end = frameBytes;
        // A few devices pad the final payload. Search a bounded trailing region.
        int eoi = -1;
        if (!frameBroken && end >= 4 && frame[0] == (byte) 0xff && frame[1] == (byte) 0xd8) {
            for (int i = end - 2; i >= Math.max(2, end - 1024); i--) {
                if (frame[i] == (byte) 0xff && frame[i + 1] == (byte) 0xd9) { eoi = i + 2; break; }
            }
        }
        if (eoi > 0) {
            frames++;
            listener.onJpeg(Arrays.copyOf(frame, eoi));
        } else if (frameBytes > 0 || frameBroken) {
            droppedFrames++;
        }
        frameBytes = 0;
    }

    private void invalidate() {
        invalidPayloads++;
        frameBroken = true;
        skipPayload = true;
    }

    private void resetPayload() {
        payloadBytes = 0;
        headerBytes = 0;
        headerLength = -1;
        headerFlags = 0;
        skipPayload = false;
    }
}
