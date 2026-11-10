import io.yingshu.framekey.ModelPostprocess;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.io.PrintWriter;
import java.util.List;

/** Runs production decoding against captured real ONNX output rows without loading any model. */
public final class OpticDetectionFixtureRunner {
    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("detections-f32.bin guns.txt output.tsv");
        List<String> guns = Files.readAllLines(Paths.get(args[1]), StandardCharsets.UTF_8);
        byte[] bytes = Files.readAllBytes(Paths.get(args[0]));
        if (bytes.length != guns.size() * 300 * 6 * 4) throw new IllegalArgumentException("Detection fixture length mismatch");
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        try (PrintWriter output = new PrintWriter(args[2], "UTF-8")) {
            for (int index = 0; index < guns.size(); index++) {
                float[][] rows = new float[300][6];
                for (int i = 0; i < rows.length; i++) for (int j = 0; j < 6; j++) rows[i][j] = buffer.getFloat();
                ModelPostprocess.DetectionPrediction p = ModelPostprocess.opticDetections(rows, guns.get(index), .8f, .8f, .1f);
                output.println(index + "\t" + p.label + "\t" + p.confidence + "\t" + p.classId + "\t" + p.reason
                        + "\t" + p.left + "\t" + p.top + "\t" + p.right + "\t" + p.bottom);
            }
        }
    }
}
