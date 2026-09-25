package com.github.xingjianluo.creditriskscoringenginejava.domain;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 一条交易流水（本地事件）。eventTime 为流水发生时间，可能重复或乱序到达。
 */
public record TransactionEvent(
        String transactionId,
        String accountId,
        BigDecimal amount,
        String payeeId,
        String jurisdiction,
        Instant eventTime
) {
}
