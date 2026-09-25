package com.github.xingjianluo.creditriskscoringenginejava.rule.impl;

import com.github.xingjianluo.creditriskscoringenginejava.model.BaselineSnapshot;
import com.github.xingjianluo.creditriskscoringenginejava.model.FactorContribution;
import com.github.xingjianluo.creditriskscoringenginejava.model.RuleContext;
import com.github.xingjianluo.creditriskscoringenginejava.model.RuleVersion;
import com.github.xingjianluo.creditriskscoringenginejava.rule.RiskRule;
import com.github.xingjianluo.creditriskscoringenginejava.version.RuleVersionRegistry;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * 金额异常偏离因子：金额达到账户历史均值的版本倍数（且历史不少于 2 笔）即加 25 分。
 * 仅在存在稳定基线时启用，避免首笔交易即误报。
 */
@Component
public class UnusualAmountRule implements RiskRule {

    public static final int MIN_HISTORY = 2;
    public static final int POINTS = 25;

    @Override
    public String code() {
        return RuleVersionRegistry.UNUSUAL_AMOUNT;
    }

    @Override
    public String factor() {
        return "金额偏离基线";
    }

    @Override
    public FactorContribution evaluate(RuleContext ctx, RuleVersion version) {
        BaselineSnapshot baseline = ctx.baseline();
        if (baseline == null || baseline.priorCount() < MIN_HISTORY) {
            return null;
        }
        BigDecimal avg;
        try {
            avg = new BigDecimal(baseline.avgAmount());
        } catch (NumberFormatException e) {
            return null;
        }
        if (avg.signum() <= 0) {
            return null;
        }
        int multiplier = version.intParam(code(), 5);
        BigDecimal threshold = avg.multiply(BigDecimal.valueOf(multiplier));
        BigDecimal amount = ctx.tx().amount();
        if (amount != null && amount.compareTo(threshold) >= 0) {
            return new FactorContribution(code(), factor(), POINTS,
                    "金额 " + amount.toPlainString() + " >= 历史均值 " + avg.toPlainString()
                            + " 的 " + multiplier + " 倍（阈值 " + threshold.toPlainString()
                            + "，版本 " + version.version() + "）");
        }
        return null;
    }
}
