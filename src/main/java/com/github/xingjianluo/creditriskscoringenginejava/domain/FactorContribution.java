package com.github.xingjianluo.creditriskscoringenginejava.domain;

/**
 * 单个因子对总分的贡献（points &gt;= 0），并给出命中依据（可解释性）。
 */
public record FactorContribution(
        FactorCode factor,
        int points,
        String evidence
) {
}
