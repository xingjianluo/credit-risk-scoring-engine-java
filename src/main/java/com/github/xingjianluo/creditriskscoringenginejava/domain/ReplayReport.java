package com.github.xingjianluo.creditriskscoringenginejava.domain;

/**
 * 历史结论复现报告：用存档的规则版本与存档基线重新计算，应与存档结论逐字段一致。
 */
public record ReplayReport(
        String transactionId,
        ScoreResult stored,
        ScoreResult recomputed,
        boolean consistent,
        String note
) {
}
