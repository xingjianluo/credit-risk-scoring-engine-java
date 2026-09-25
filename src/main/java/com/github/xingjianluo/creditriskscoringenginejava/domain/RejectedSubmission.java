package com.github.xingjianluo.creditriskscoringenginejava.domain;

import java.time.Instant;

/**
 * 因字段缺失/金额非法/时间异常而被拒绝（未静默丢弃）的流水记录。
 */
public record RejectedSubmission(
        String transactionId,
        RejectReason reason,
        String detail,
        Instant receivedAt
) {
}
