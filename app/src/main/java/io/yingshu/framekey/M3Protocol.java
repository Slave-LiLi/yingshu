package io.yingshu.framekey;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Independently implemented byte encoding from the documented hardware behavior. */
public final class M3Protocol {
    public static final String SERVICE_UUID = "0000fff0-0000-1000-8000-00805f9b34fb";
    public static final String CCCD_UUID = "00002902-0000-1000-8000-00805f9b34fb";

    private M3Protocol() { }

    public static boolean matchesDeviceName(String name) {
        return name != null && name.toUpperCase(Locale.ROOT).contains("HCL");
    }

    public static byte[] serialQuery() {
        return new byte[] {(byte) 0xAA, (byte) 0xBB};
    }

    public static byte[] handshakeFollowup() {
        return new byte[] {(byte) 0xBB, 0x62};
    }

    /** Null means this is not a nonempty serial response. Serial length is not fixed. */
    public static String parseSerial(byte[] bytes) {
        if (bytes == null || bytes.length < 3 || (bytes[0] & 255) != 0xAA
                || (bytes[1] & 255) != 0xBB) return null;
        return hex(Arrays.copyOfRange(bytes, 2, bytes.length));
    }

    /** These are observed notification bytes, not proof of downstream key execution. */
    public static byte[] notificationKeys(byte[] bytes) {
        if (bytes == null || bytes.length < 3) return new byte[0];
        if ((bytes[0] & 255) == 0xAA && (bytes[1] & 255) == 0xBB) return new byte[0];
        return Arrays.copyOfRange(bytes, 2, bytes.length);
    }

    /** Four separate GATT writes; modifier bits 0..7 map to usage E0..E7. */
    public static List<byte[]> keyFrames(int mainKey, int modifierMask) {
        if (mainKey < 0 || mainKey > 255 || modifierMask < 0 || modifierMask > 255)
            throw new IllegalArgumentException("Key and modifier mask must be bytes");
        byte[] first = new byte[2 + Integer.bitCount(modifierMask)];
        first[0] = (byte) 0xBB;
        int pos = 1;
        for (int bit = 0; bit < 8; bit++) {
            if ((modifierMask & (1 << bit)) != 0) first[pos++] = (byte) (0xE0 + bit);
        }
        first[pos] = (byte) mainKey;
        List<byte[]> frames = new ArrayList<>(4);
        frames.add(first);
        frames.add(new byte[first.length]);
        frames.add(handshakeFollowup());
        frames.add(new byte[] {0, 0});
        return frames;
    }

    public static String hex(byte[] bytes) {
        if (bytes == null) return "";
        final char[] alphabet = "0123456789ABCDEF".toCharArray();
        char[] chars = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int value = bytes[i] & 255;
            chars[i * 2] = alphabet[value >>> 4];
            chars[i * 2 + 1] = alphabet[value & 15];
        }
        return new String(chars);
    }
}
