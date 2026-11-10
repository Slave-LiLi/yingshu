import io.yingshu.framekey.*;

public final class RuleGateTest {
    static int assertions;
    static void check(boolean value,String name){assertions++;if(!value)throw new AssertionError(name);}
    public static void main(String[] args){
        RuleBook book=new RuleBook();check(book.rules.length==38,"thirty-seven combinations plus unknown");
        for(RuleBook.Rule r:book.rules)check(r.usage==0&&r.modifiers==0,"unassigned by default");
        check(book.find("R99","four_x")==null,"unsupported R99 state has no rule");
        check(book.find("VK47","four_x")!=null,"variable 2-4 uses four_x");
        RuleGate gate=new RuleGate();String a="VK47|four_x:63:0",b="R301|two_x:64:0";
        check(!gate.observe(a,0),"first frame");check(!gate.observe(a,100),"two frames");
        check(!gate.observe(a,200),"not stable for 300ms");check(gate.observe(a,300),"confirmed");
        check(gate.observe(a,400),"busy BLE retries on fresh result");gate.submitted(a);
        check(!gate.observe(a,500),"no repeated output");check(!gate.observe(null,600),"single missing frame");
        check(!gate.observe(a,700),"reconfirm after miss");check(!gate.observe(a,800),"still same sent key");
        check(!gate.observe(a,1000),"short miss does not repeat");
        check(!gate.observe(b,1100),"switch candidate");check(!gate.observe(a,1200),"switch cancels earlier candidate");
        check(!gate.observe(b,1300),"new switch");check(!gate.observe(b,1400),"second switch frame");
        check(gate.observe(b,1600),"new combination confirmed");gate.submitted(b);
        check(!gate.observe(null,1700),"lost state");check(!gate.observe(null,2200),"long loss");
        check(!gate.observe(b,2300),"return after loss");check(!gate.observe(b,2400),"return second frame");
        check(gate.observe(b,2600),"long loss permits one new output");gate.submitted(b);
        check(!gate.observe(b,4000),"stale frame gap clears confirmation");
        gate.reset();check(!gate.observe(a,5000),"reset disarms");
        interruptionRequiresReconfirmation();
        check(KeyCatalog.USAGES[KeyCatalog.indexOf(0x68)]==0x68,"F13 code");
        check("F13".equals(KeyCatalog.name(0x68,0)),"F13 label");
        check("Ctrl+Shift+F6".equals(KeyCatalog.name(0x3f,3)),"modifier display");
        System.out.println("RuleGateTest: "+assertions+" assertions passed");
    }

    private static void interruptionRequiresReconfirmation(){
        RuleGate gate=new RuleGate();String binding="R99|one_x:63:0";
        check(!gate.observe(binding,0),"before interruption first frame");
        check(!gate.observe(binding,150),"before interruption partial confirmation");
        // WAITING/stop/recognition toggle invalidate this partial candidate immediately.
        gate.reset();
        check(!gate.observe(binding,300),"recovered frame cannot finish interrupted confirmation");
        check(!gate.observe(binding,450),"recovery still requires three observations");
        check(gate.observe(binding,600),"fresh recovery confirms after its own 300ms");
        gate.submitted(binding);
        check(gate.isSubmitted(binding),"submitted state before video loss");
        gate.reset();
        check(!gate.isSubmitted(binding),"video loss clears old submission status");
        check(!gate.observe(binding,10000),"long interruption recovery cannot send immediately");
        check(!gate.observe(binding,10150),"long interruption recovery second observation");
        check(gate.observe(binding,10300),"long interruption permits one newly confirmed submission");
        gate.submitted(binding);
        check(!gate.observe(binding,10400),"continuous recovered frames do not repeat submission");
    }
}
