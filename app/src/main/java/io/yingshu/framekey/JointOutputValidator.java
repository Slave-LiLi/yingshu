package io.yingshu.framekey;

/** Runtime numerical failures must never become a normal unknown-gun action. */
public final class JointOutputValidator {
    private JointOutputValidator() {}
    public static void validate(float[][] rows) {
        if(rows==null||rows.length!=300)throw new IllegalArgumentException("Joint output must have 300 rows");
        for(float[] row:rows){
            if(row==null||row.length!=6)throw new IllegalArgumentException("Joint output row must have six values");
            for(float value:row)if(Float.isNaN(value)||Float.isInfinite(value))
                throw new IllegalArgumentException("Joint output contains a nonfinite number");
            if(row[4]<0||row[4]>1||row[5]!=(int)row[5]||row[5]<0||row[5]>12)
                throw new IllegalArgumentException("Joint output contains an invalid score or class");
        }
    }
}
