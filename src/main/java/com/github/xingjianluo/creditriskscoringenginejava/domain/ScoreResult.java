package com.github.xingjianluo.creditriskscoringenginejava.domain;

import java.time.Instant;
import java.util.List;

/**
 * 一次评分的不可变结论。rulesVersion 使结论可复现回溯。
 */
public record ScoreResult(
        String transactionId,
        String accountId,
        int totalScore,
        RiskLevel riskLevel,
        List<FactorContribution> contributions,
        String rulesVersion,
        Instant scoredAt,
        List<String> baselineTransactionIds
) {
    public ScoreResult {
        baselineTransactionIds = baselineTransactionIds == null
                ? List.of() : List.copyOf(baselineTransactionIds);
    }

    /** 兼容无基线快照的构造（快照为空）。 */
    public ScoreResult(String transactionId, String accountId, int totalScore,
                       RiskLevel riskLevel, List<FactorContribution> contributions,
                       String rulesVersion, Instant scoredAt) {
        this(transactionId, accountId, totalScore, riskLevel, contributions,
                rulesVersion, scoredAt, List.of());
    }
}
