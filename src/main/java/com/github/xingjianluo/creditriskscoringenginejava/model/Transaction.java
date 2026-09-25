package com.github.xingjianluo.creditriskscoringenginejava.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 一笔交易流水（评分引擎的输入信号）。
 */
public record Transaction(
        String transactionId,
        String accountId,
        BigDecimal amount,
        String currency,
        Instant eventTime,
        String counterpartyAccount,
        String counterpartyCountry,
        String channel) {
}
