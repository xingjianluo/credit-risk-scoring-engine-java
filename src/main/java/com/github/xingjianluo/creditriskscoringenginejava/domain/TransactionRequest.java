package com.github.xingjianluo.creditriskscoringenginejava.domain;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 流水提交的原始入参（字段允许缺失/非法，校验器会给出可区分的拒绝原因）。
 */
public record TransactionRequest(
        String transactionId,
        String accountId,
        BigDecimal amount,
        String payeeId,
        String jurisdiction,
        Instant eventTime
) {
}
