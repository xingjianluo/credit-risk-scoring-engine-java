package com.github.xingjianluo.creditriskscoringenginejava.model;

import java.time.Instant;
import java.util.List;

/**
 * 一次评分的不可变结论。重复提交返回同一结果（幂等）。
 */
public record RiskScoreResult(
        String transactionId,
        String accountId,
        int totalScore,
        RiskLevel level,
        String ruleVersion,
        List<FactorContribution> contributions,
        BaselineSnapshot baseline,
        Instant scoredAt,
        Instant eventTime) {
}
