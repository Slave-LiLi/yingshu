package io.yingshu.framekey;

/** Current-frame confirmation; no timers can send a key without a fresh observation. */
public final class RuleGate {
    private String candidate="",sent="";
    private long firstAt,lastAt=-1,missingAt=-1;
    private int count;
    public void reset(){candidate="";sent="";firstAt=0;lastAt=-1;missingAt=-1;count=0;}
    public boolean observe(String binding,long now){
        if(lastAt>=0&&(now<lastAt||now-lastAt>500))reset();
        lastAt=now;
        if(binding==null||binding.isEmpty()){
            candidate="";count=0;
            if(missingAt<0)missingAt=now;
            if(now-missingAt>=500)sent="";
            return false;
        }
        missingAt=-1;
        if(!binding.equals(candidate)){candidate=binding;firstAt=now;count=1;return false;}
        count++;
        return count>=3&&now-firstAt>=300&&!binding.equals(sent);
    }
    /** Only mark after the BLE queue accepted the complete four-frame key action. */
    public void submitted(String binding){if(binding.equals(candidate))sent=binding;}
    public boolean isSubmitted(String binding){return binding!=null&&binding.equals(sent);}
}
