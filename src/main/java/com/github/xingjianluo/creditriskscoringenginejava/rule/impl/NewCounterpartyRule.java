package com.github.xingjianluo.creditriskscoringenginejava.rule.impl;

import com.github.xingjianluo.creditriskscoringenginejava.model.BaselineSnapshot;
import com.github.xingjianluo.creditriskscoringenginejava.model.FactorContribution;
import com.github.xingjianluo.creditriskscoringenginejava.model.RuleContext;
import com.github.xingjianluo.creditriskscoringenginejava.model.RuleVersion;
import com.github.xingjianluo.creditriskscoringenginejava.rule.RiskRule;
import com.github.xingjianluo.creditriskscoringenginejava.version.RuleVersionRegistry;
import org.springframework.stereotype.Component;

/**
 * 新交易对手因子：对手未出现在账户历史基线中（含首笔交易）即按版本参数加分。
 */
@Component
public class NewCounterpartyRule implements RiskRule {

    @Override
    public String code() {
        return RuleVersionRegistry.NEW_COUNTERPARTY;
    }

    @Override
    public String factor() {
        return "新交易对手";
    }

    @Override
    public FactorContribution evaluate(RuleContext ctx, RuleVersion version) {
        int points = version.intParam(code(), 15);
        BaselineSnapshot baseline = ctx.baseline();
        String counterparty = ctx.tx().counterpartyAccount();
        if (counterparty != null && (baseline == null || !baseline.knownCounterparties().contains(counterparty))) {
            return new FactorContribution(code(), factor(), points,
                    "交易对手 " + counterparty + " 不在历史对手集合中"
                            + "（历史对手数=" + (baseline == null ? 0 : baseline.knownCounterparties().size())
                            + "，版本 " + version.version() + "）");
        }
        return null;
    }
}
