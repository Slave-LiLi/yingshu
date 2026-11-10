import io.yingshu.framekey.*;
import java.util.HashSet;

/** Result-to-rule policy and temporal fallback behavior without Android or BLE. */
public final class RecognitionRuleSelectorTest {
    private static int assertions;
    private static void check(boolean ok,String why){assertions++;if(!ok)throw new AssertionError(why);}
    private static RuleBook.Rule select(RuleBook book,String gun,String optic,long captured,long now){
        return RecognitionRuleSelector.select(book,gun,optic,true,true,true,captured,now);
    }
    public static void main(String[] args){
        RuleBook book=new RuleBook();
        String[] oldIds={"VK47|none","VK47|one_x","VK47|two_x","VK47|three_x","VK47|four_x",
            "R301|none","R301|one_x","R301|two_x","R301|three_x","R301|four_x","R99|none","R99|one_x",
            "NEMESIS|none","NEMESIS|one_x","NEMESIS|two_x","NEMESIS|three_x","NEMESIS|four_x"};
        check(book.rules.length==38,"exactly 38 editable rules");
        HashSet<String> ids=new HashSet<>();
        for(int i=0;i<oldIds.length;i++)check(oldIds[i].equals(book.rules[i].id),"old persisted ID/order "+i);
        for(RuleBook.Rule r:book.rules){check(ids.add(r.id),"unique stable ID");check(r.usage==0&&r.modifiers==0,"new install is unassigned");}
        RuleBook.Rule unknown=book.unknownGunRule();
        check("unknown_gun".equals(unknown.id),"stable unknown preference key");
        check("未识别到枪械".equals(unknown.title()),"unknown rule user label");
        // The old fallback ID persists even if its display position changes in a later UI.
        book.rules[book.rules.length-1]=book.rules[0];book.rules[0]=unknown;
        check(book.unknownGunRule()==unknown,"fallback identity does not depend on array index");
        check(select(book,"","unknown",1000,1100)==unknown,"reordered UI still selects original fallback");
        book.rules[0]=book.rules[book.rules.length-1];book.rules[book.rules.length-1]=unknown;
        String[] newGuns={"ALTERNATOR","LSTAR","PROWLER","CAR"};
        String[] newNames={"转换者","L-STAR","猎兽","CAR"};
        for(int i=0;i<newGuns.length;i++){
            check(newNames[i].equals(RuleBook.gunName(newGuns[i])),"new gun display name "+newGuns[i]);
            for(String optic:new String[]{"none","one_x","two_x","three_x","four_x"}){
                RuleBook.Rule rule=book.find(newGuns[i],optic);
                check(rule!=null&&rule.id.equals(newGuns[i]+"|"+optic),"new stable combo ID");
                check(select(book,newGuns[i],optic,1000,1100)==rule,"new combination selects its own rule");
            }
        }
        check(book.find("NEMESIS","four_x").title().equals("复仇女神 · 4 倍（含 2–4 倍镜）"),"Nemesis user label");
        check(select(book,"NEMESIS","four_x",1000,1100)==book.find("NEMESIS","four_x"),"Nemesis has ordinary optic rules");
        for(String gun:new String[]{null,"","unknown"})check(select(book,gun,"unknown",1000,1100)==unknown,"completed unknown chooses fallback");
        for(String gun:new String[]{"VK47","R301","R99","NEMESIS","ALTERNATOR","LSTAR","PROWLER","CAR"})check(select(book,gun,"unknown",1000,1100)==null,"known gun with unknown optic is not fallback");
        check(select(book,"KRABER","four_x",1000,1100)==null,"unsupported named gun never masquerades as empty result");
        check(select(book,"R99","four_x",1000,1100)==null,"unsupported R99 optic cannot fall back");
        check(select(book,"","unknown",1000,1500)==unknown,"500ms inclusive");
        check(select(book,"","unknown",1000,1501)==null,"stale unknown cannot send");
        check(select(book,"","unknown",1100,1000)==null,"future capture cannot send");
        check(select(book,"","unknown",-1,1000)==null,"invalid capture cannot send");
        for(boolean completed:new boolean[]{false,true})for(boolean live:new boolean[]{false,true})for(boolean enabled:new boolean[]{false,true}){
            RuleBook.Rule r=RecognitionRuleSelector.select(book,"","unknown",completed,live,enabled,1000,1100);
            check((r==unknown)==(completed&&live&&enabled),"only actual normal active inference may select fallback");
        }
        // Real RuleGate behavior after the real selector resolves the fallback.
        unknown.usage=0x3f;unknown.modifiers=0;String binding=select(book,"","unknown",2000,2000).bindingId();
        RuleGate gate=new RuleGate();
        check(!gate.observe(binding,2000),"unknown first observation");
        check(!gate.observe(binding,2150),"unknown second observation");
        check(gate.observe(binding,2300),"stable third observation permits one fallback");
        gate.submitted(binding);
        check(!gate.observe(binding,2450),"continuous unknown does not repeat");
        String known=select(book,"R99","one_x",2500,2500).bindingId();
        check(!gate.observe(known,2500),"known result changes candidate");
        check(!gate.observe(known,2650),"known result needs confirmation");
        check(gate.observe(known,2800),"known state confirms after fallback");gate.submitted(known);
        check(!gate.observe(binding,2900),"new unknown episode starts fresh");
        gate.reset(); // MainActivity uses this on stop/disabled/error/stale result.
        check(!gate.observe(binding,3050),"reset invalidates partial unknown confirmation");
        check(!gate.observe(binding,3200),"reset needs second fresh observation");
        check(gate.observe(binding,3350),"reset permits newly confirmed fallback only");
        // Measured phone-like cadence: each result is 300ms old and results arrive 312ms apart.
        // The UI may expire between callbacks, but only real observations participate in RuleGate.
        gate.reset();
        for(int i=0;i<4;i++){
            long captured=5000+i*312,delivered=captured+300;
            RuleBook.Rule current=select(book,"","unknown",captured,delivered);
            check(current==unknown,"slow but individually fresh normal result");
            boolean ready=gate.observe(current.bindingId(),delivered);
            check(ready==(i==2),"low-rate confirmation then single submission "+i);
            if(ready)gate.submitted(current.bindingId());
        }
        System.out.println("RecognitionRuleSelectorTest: "+assertions+" assertions passed");
    }
}
