package com.github.xingjianluo.creditriskscoringenginejava.rule.impl;

import com.github.xingjianluo.creditriskscoringenginejava.model.FactorContribution;
import com.github.xingjianluo.creditriskscoringenginejava.model.RuleContext;
import com.github.xingjianluo.creditriskscoringenginejava.model.RuleVersion;
import com.github.xingjianluo.creditriskscoringenginejava.rule.RiskRule;
import com.github.xingjianluo.creditriskscoringenginejava.version.RuleVersionRegistry;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * 大额交易因子：金额达到版本阈值即加 20 分（金额越大不会减分，单调）。
 */
@Component
public class LargeAmountRule implements RiskRule {

    public static final int POINTS = 20;

    @Override
    public String code() {
        return RuleVersionRegistry.LARGE_AMOUNT;
    }

    @Override
    public String factor() {
        return "大额交易";
    }

    @Override
    public FactorContribution evaluate(RuleContext ctx, RuleVersion version) {
        BigDecimal threshold = version.amountParam(code(), new BigDecimal("10000"));
        BigDecimal amount = ctx.tx().amount();
        if (amount != null && amount.compareTo(threshold) >= 0) {
            return new FactorContribution(code(), factor(), POINTS,
                    "金额 " + amount.toPlainString() + " >= 阈值 " + threshold.toPlainString()
                            + " (版本 " + version.version() + ")");
        }
        return null;
    }
}
