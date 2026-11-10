package io.yingshu.framekey;

/** Select only from a completed current-frame result. Absence of a result is never a fallback. */
public final class RecognitionRuleSelector {
    private RecognitionRuleSelector() {}
    public static boolean isUsableResult(boolean completedNormally, boolean live, boolean enabled,
                                         long capturedAtMs, long nowMs) {
        return completedNormally && live && enabled && capturedAtMs >= 0 && nowMs >= capturedAtMs
                && nowMs-capturedAtMs <= 500;
    }
    public static RuleBook.Rule select(RuleBook book,String gun,String optic,boolean completedNormally,
                                        boolean live,boolean enabled,long capturedAtMs,long nowMs) {
        if(book==null||!isUsableResult(completedNormally,live,enabled,capturedAtMs,nowMs))return null;
        if(gun==null||gun.isEmpty()||"unknown".equals(gun))return book.unknownGunRule();
        // Known gun + unknown optic, unsupported gun, or invalid state has no fallback rule.
        return book.find(gun,optic);
    }
}
