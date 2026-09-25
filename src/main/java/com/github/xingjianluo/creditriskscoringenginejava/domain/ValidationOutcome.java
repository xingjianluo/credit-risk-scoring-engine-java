package com.github.xingjianluo.creditriskscoringenginejava.domain;

/**
 * 字段校验结果：accepted 为 true 时 event 可用，否则携带拒绝原因与细节。
 */
public record ValidationOutcome(
        boolean accepted,
        TransactionEvent event,
        RejectReason reason,
        String detail
) {
    public static ValidationOutcome ok(TransactionEvent event) {
        return new ValidationOutcome(true, event, null, null);
    }

    public static ValidationOutcome reject(RejectReason reason, String detail) {
        return new ValidationOutcome(false, null, reason, detail);
    }
}
