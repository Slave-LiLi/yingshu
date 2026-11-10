import io.yingshu.framekey.M3Protocol;
import java.util.List;

public final class M3ProtocolTest {
    private static int assertions;
    private static void eq(Object actual, Object expected) {
        assertions++;
        if (expected == null ? actual != null : !expected.equals(actual))
            throw new AssertionError("Expected " + expected + ", got " + actual);
    }
    public static void main(String[] args) {
        eq(M3Protocol.matchesDeviceName("My-hcl-M3"), true);
        eq(M3Protocol.matchesDeviceName("OTHER"), false);
        eq(M3Protocol.matchesDeviceName(null), false);
        eq(M3Protocol.hex(M3Protocol.serialQuery()), "AABB");
        eq(M3Protocol.parseSerial(new byte[] {(byte) 0xAA, (byte) 0xBB, 1, 2, 3}), "010203");
        eq(M3Protocol.parseSerial(new byte[] {(byte) 0xAA, (byte) 0xBB}), null);
        eq(M3Protocol.parseSerial(new byte[] {(byte) 0xBB, 0x62, 0}), null);
        eq(M3Protocol.parseSerial(null), null);
        eq(M3Protocol.hex(M3Protocol.notificationKeys(new byte[] {1, 2, 4, 0})), "0400");
        eq(M3Protocol.notificationKeys(new byte[] {(byte) 0xAA, (byte) 0xBB, 4}).length, 0);
        List<byte[]> plain = M3Protocol.keyFrames(4, 0);
        eq(plain.size(), 4);
        eq(M3Protocol.hex(plain.get(0)), "BB04");
        eq(M3Protocol.hex(plain.get(1)), "0000");
        eq(M3Protocol.hex(plain.get(2)), "BB62");
        eq(M3Protocol.hex(plain.get(3)), "0000");
        List<byte[]> modified = M3Protocol.keyFrames(4, 0x81);
        eq(M3Protocol.hex(modified.get(0)), "BBE0E704");
        eq(M3Protocol.hex(modified.get(1)), "00000000");
        eq(M3Protocol.hex(M3Protocol.keyFrames(4, 255).get(0)), "BBE0E1E2E3E4E5E6E704");
        boolean rejected = false;
        try { M3Protocol.keyFrames(256, 0); } catch (IllegalArgumentException expected) { rejected = true; }
        eq(rejected, true);
        plain.get(2)[0] = 0;
        eq(M3Protocol.hex(M3Protocol.handshakeFollowup()), "BB62");
        System.out.println("M3 protocol tests passed: " + assertions);
    }
}
