package com.github.xingjianluo.creditriskscoringenginejava.model;

/**
 * 单个风险因子的贡献：规则码、因子名、非负得分、命中证据。
 */
public record FactorContribution(
        String ruleCode,
        String factor,
        int points,
        String evidence) {
}
