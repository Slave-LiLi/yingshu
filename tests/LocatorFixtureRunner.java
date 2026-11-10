import io.yingshu.framekey.ImageTensor;
import io.yingshu.framekey.ModelPostprocess;
import java.io.DataInputStream;
import java.io.FileInputStream;
import java.io.PrintWriter;

/** Binary fixture reader used only for differential Python/Java selection tests. */
public final class LocatorFixtureRunner {
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("input.bin output.tsv");
        int[] offsets = ImageTensor.horizontalOffsets(-40, 40, 4);
        try (DataInputStream input = new DataInputStream(new FileInputStream(args[0]));
             PrintWriter output = new PrintWriter(args[1], "UTF-8")) {
            int count = input.readInt();
            for (int i = 0; i < count; i++) {
                String gun = input.readUTF();
                float threshold = input.readFloat(), minMargin = input.readFloat();
                float conflictThreshold = input.readFloat(), conflictMargin = input.readFloat();
                float[][] logits = new float[21][6];
                for (int candidate = 0; candidate < 21; candidate++) {
                    for (int id = 0; id < 6; id++) logits[candidate][id] = input.readFloat();
                }
                ModelPostprocess.LocatorPrediction p = ModelPostprocess.locate(logits, gun, offsets,
                        threshold, minMargin, conflictThreshold, conflictMargin);
                output.println(i + "\t" + p.label + "\t" + p.confidence + "\t" + p.locatorConfidence
                        + "\t" + p.offsetX + "\t" + p.candidateIndex + "\t" + p.reason);
            }
            if (input.read() != -1) throw new IllegalArgumentException("Trailing fixture data");
        }
    }
}
