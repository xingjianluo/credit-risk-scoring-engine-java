package com.github.xingjianluo.creditriskscoringenginejava.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 某一版本的评分规则与阈值（不可变）。
 *
 * <p>评分模型（兼容规则）：所有因子仅在命中时贡献非负分值，总分是各因子贡献之和，
 * 因此总分对每个风险因素单调（任何风险因素命中/加强都不会让分数下降）。
 * 规则一旦激活即为只读快照，历史评分结论永远引用其当时的版本。
 *
 * @param amountLargeThreshold       单笔大额阈值（&gt;= 命中）
 * @param amountLargePoints          大额因子分值（非负）
 * @param rapidWindowSeconds         短时连续交易窗口秒数（与上一笔同账户流水的事件时间差）
 * @param rapidSuccessionPoints      短时连续因子分值
 * @param newPayeePoints             陌生收款方因子分值
 * @param highRiskJurisdictions      高风险司法辖区代码集合（大写）
 * @param highRiskJurisdictionPoints 高风险辖区因子分值
 * @param offHoursStartHour          深夜时段起始小时（UTC，含）
 * @param offHoursEndHour            深夜时段结束小时（UTC，不含；允许跨零点）
 * @param offHoursPoints             深夜时段因子分值
 * @param velocityWindowSeconds      滚动累计窗口秒数（仅统计事件时间严格早于本流水的历史流水）
 * @param velocityAmountThreshold    窗口内历史累计金额阈值（&gt;= 命中）
 * @param velocityPoints             滚动累计突增因子分值
 * @param mediumThreshold            总分 &gt;= 该值为 MEDIUM
 * @param highThreshold              总分 &gt;= 该值为 HIGH
 * @param autoCaseThreshold          总分 &gt;= 该值自动生成审查案件
 */
public record RuleSet(
        String version,
        Instant activatedAt,
        BigDecimal amountLargeThreshold,
        int amountLargePoints,
        long rapidWindowSeconds,
        int rapidSuccessionPoints,
        int newPayeePoints,
        Set<String> highRiskJurisdictions,
        int highRiskJurisdictionPoints,
        int offHoursStartHour,
        int offHoursEndHour,
        int offHoursPoints,
        long velocityWindowSeconds,
        BigDecimal velocityAmountThreshold,
        int velocityPoints,
        int mediumThreshold,
        int highThreshold,
        int autoCaseThreshold
) {
    public RuleSet {
        // 防御性拷贝，保证版本快照不可变
        highRiskJurisdictions = highRiskJurisdictions == null
                ? Set.of() : Set.copyOf(highRiskJurisdictions);
    }

    public RiskLevel levelOf(int totalScore) {
        if (totalScore >= highThreshold) {
            return RiskLevel.HIGH;
        }
        if (totalScore >= mediumThreshold) {
            return RiskLevel.MEDIUM;
        }
        return RiskLevel.LOW;
    }

    /**
     * 规则定义自检。返回非空错误信息列表（空列表表示合法）。
     */
    public List<String> validate() {
        List<String> errors = new ArrayList<>();
        if (version == null || version.isBlank()) {
            errors.add("version 不能为空");
        }
        if (activatedAt == null) {
            errors.add("activatedAt 不能为空");
        }
        if (amountLargeThreshold == null || amountLargeThreshold.signum() <= 0) {
            errors.add("amountLargeThreshold 必须为正数");
        }
        if (velocityAmountThreshold == null || velocityAmountThreshold.signum() <= 0) {
            errors.add("velocityAmountThreshold 必须为正数");
        }
        checkNonNegative(errors, amountLargePoints, "amountLargePoints");
        checkNonNegative(errors, rapidSuccessionPoints, "rapidSuccessionPoints");
        checkNonNegative(errors, newPayeePoints, "newPayeePoints");
        checkNonNegative(errors, highRiskJurisdictionPoints, "highRiskJurisdictionPoints");
        checkNonNegative(errors, offHoursPoints, "offHoursPoints");
        checkNonNegative(errors, velocityPoints, "velocityPoints");
        if (rapidWindowSeconds <= 0) {
            errors.add("rapidWindowSeconds 必须为正数");
        }
        if (velocityWindowSeconds <= 0) {
            errors.add("velocityWindowSeconds 必须为正数");
        }
        if (invalidHour(offHoursStartHour) || invalidHour(offHoursEndHour)
                || offHoursStartHour == offHoursEndHour) {
            errors.add("offHours 小时必须在 [0,23] 且起止不相等");
        }
        if (!(0 <= mediumThreshold && mediumThreshold <= highThreshold
                && highThreshold <= 100)) {
            errors.add("阈值需满足 0 <= medium <= high <= 100");
        }
        if (autoCaseThreshold < 0 || autoCaseThreshold > 100) {
            errors.add("autoCaseThreshold 必须在 [0,100]");
        }
        return errors;
    }

    private static void checkNonNegative(List<String> errors, int points, String name) {
        if (points < 0) {
            errors.add(name + " 不能为负");
        }
    }

    private static boolean invalidHour(int hour) {
        return hour < 0 || hour > 23;
    }

    /**
     * 内置基线版本 v1 的规则参数。
     */
    public static RuleSet v1(Instant now) {
        return new RuleSet(
                "v1",
                now,
                new BigDecimal("50000.00"), 20,
                60L, 15,
                10,
                new HashSet<>(Set.of("XX", "ZZ", "OFAC")), 25,
                0, 6, 10,
                300L, new BigDecimal("100000.00"), 20,
                30, 60, 50
        );
    }
}
