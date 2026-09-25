package com.github.xingjianluo.creditriskscoringenginejava.model;

import java.time.Instant;

/**
 * 被拒绝流水的留痕记录，非法流水不会被静默丢弃。
 */
public record RejectionRecord(
        String transactionId,
        String accountId,
        RejectionReason reason,
        String detail,
        Instant rejectedAt,
        String fingerprint) {
}
