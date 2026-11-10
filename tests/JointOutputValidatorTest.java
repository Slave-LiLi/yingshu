import io.yingshu.framekey.JointOutputValidator;

public final class JointOutputValidatorTest {
    private static int assertions;
    private static void valid(float[][] rows){JointOutputValidator.validate(rows);assertions++;}
    private static void rejects(float[][] rows){
        boolean failed=false;try{JointOutputValidator.validate(rows);}catch(IllegalArgumentException expected){failed=true;}
        assertions++;if(!failed)throw new AssertionError("Invalid runtime output accepted");
    }
    private static float[][] rows(){return new float[300][6];}
    public static void main(String[] args){
        valid(rows()); // Legitimate all-low-confidence output is a normal unknown, not a numeric error.
        float[][] good=rows();good[299]=new float[]{-2,96,330,220,.99f,12};valid(good);
        for(int cls=0;cls<13;cls++){float[][] x=rows();x[5][5]=cls;valid(x);}
        rejects(null);rejects(new float[299][6]);rejects(new float[301][6]);
        for(int coordinate=0;coordinate<6;coordinate++)for(float value:new float[]{Float.NaN,Float.POSITIVE_INFINITY,Float.NEGATIVE_INFINITY}){
            float[][] x=rows();x[40][coordinate]=value;rejects(x);
        }
        for(float score:new float[]{-.001f,1.001f}){float[][] x=rows();x[5][4]=score;rejects(x);}
        for(float cls:new float[]{-1,13,4.5f,Float.MAX_VALUE}){float[][] x=rows();x[5][5]=cls;rejects(x);}
        float[][] shortRow=rows();shortRow[299]=new float[5];rejects(shortRow);
        float[][] absentRow=rows();absentRow[299]=null;rejects(absentRow);
        System.out.println("JointOutputValidatorTest: "+assertions+" assertions passed");
    }
}
