package io.yingshu.framekey;

/** Thirty-seven weapon/optic combinations and one explicit unknown-gun rule. No default keys. */
public final class RuleBook {
    public static final String UNKNOWN_GUN_ID="unknown_gun";
    public static final class Rule {
        public final String gun, optic, id;
        public int usage, modifiers;
        Rule(String gun,String optic){this(gun,optic,gun+"|"+optic);}
        Rule(String gun,String optic,String id){this.gun=gun;this.optic=optic;this.id=id;}
        public String title(){return UNKNOWN_GUN_ID.equals(id)?"未识别到枪械":gunName(gun)+" · "+opticName(optic);}
        public String bindingId(){return id+":"+usage+":"+modifiers;}
    }
    private final Rule unknownGun=new Rule("unknown","unknown",UNKNOWN_GUN_ID);
    public final Rule[] rules=new Rule[38];
    public RuleBook(){
        String[] optics={"none","one_x","two_x","three_x","four_x"};int index=0;
        for(String gun:new String[]{"VK47","R301"})for(String optic:optics)rules[index++]=new Rule(gun,optic);
        rules[index++]=new Rule("R99","none");rules[index++]=new Rule("R99","one_x");
        for(String optic:optics)rules[index++]=new Rule("NEMESIS",optic);
        for(String gun:new String[]{"ALTERNATOR","LSTAR","PROWLER","CAR"})
            for(String optic:optics)rules[index++]=new Rule(gun,optic);
        rules[index]=unknownGun;
    }
    public Rule find(String gun,String optic){for(Rule r:rules)if(r.gun.equals(gun)&&r.optic.equals(optic))return r;return null;}
    public Rule unknownGunRule(){return unknownGun;}
    public static String gunName(String gun){
        if("VK47".equals(gun))return "VK";
        if("NEMESIS".equals(gun))return "复仇女神";
        if("ALTERNATOR".equals(gun))return "转换者";
        if("LSTAR".equals(gun))return "L-STAR";
        if("PROWLER".equals(gun))return "猎兽";
        return gun;
    }
    public static String opticName(String optic){
        if("none".equals(optic))return "无镜";if("one_x".equals(optic))return "1 倍";
        if("two_x".equals(optic))return "2 倍";if("three_x".equals(optic))return "3 倍";
        if("four_x".equals(optic))return "4 倍（含 2–4 倍镜）";return "倍镜未确定";
    }
}
