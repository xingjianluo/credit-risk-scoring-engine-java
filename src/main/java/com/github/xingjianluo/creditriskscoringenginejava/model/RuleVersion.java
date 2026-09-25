package com.github.xingjianluo.creditriskscoringenginejava.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 评分规则与阈值的不可变版本定义。
 *
 * @param version           版本号，如 v1 / v2
 * @param createdAt         版本生效（登记）时间
 * @param highThreshold     HIGH 下限（含）
 * @param criticalThreshold CRITICAL 下限（含），MEDIUM 固定从 30 起
 * @param parameters        各因子规则阈值参数
 * @param compatibleWith    可兼容读取的旧版本号
 */
public record RuleVersion(
        String version,
        Instant createdAt,
        int highThreshold,
        int criticalThreshold,
        List<RuleParameter> parameters,
        List<String> compatibleWith) {

    public static final int MEDIUM_THRESHOLD = 30;

    /** 依据本版本阈值将分数映射为风险等级。 */
    public RiskLevel levelOf(int score) {
        if (score >= criticalThreshold) {
            return RiskLevel.CRITICAL;
        }
        if (score >= highThreshold) {
            return RiskLevel.HIGH;
        }
        if (score >= MEDIUM_THRESHOLD) {
            return RiskLevel.MEDIUM;
        }
        return RiskLevel.LOW;
    }

    public RuleParameter parameter(String code) {
        return parameters.stream().filter(p -> p.code().equals(code)).findFirst().orElse(null);
    }

    /** 读取整型参数；缺失时返回给定默认值。 */
    public int intParam(String code, int defaultValue) {
        RuleParameter p = parameter(code);
        return p == null ? defaultValue : Integer.parseInt(p.value());
    }

    /** 读取金额参数（字符串存储以保证版本定义可序列化）；缺失时返回给定默认值。 */
    public BigDecimal amountParam(String code, BigDecimal defaultValue) {
        RuleParameter p = parameter(code);
        return p == null ? defaultValue : new BigDecimal(p.value());
    }
}
