package com.github.xingjianluo.creditriskscoringenginejava.rule.impl;

import com.github.xingjianluo.creditriskscoringenginejava.model.FactorContribution;
import com.github.xingjianluo.creditriskscoringenginejava.model.RuleContext;
import com.github.xingjianluo.creditriskscoringenginejava.model.RuleVersion;
import com.github.xingjianluo.creditriskscoringenginejava.rule.RiskRule;
import com.github.xingjianluo.creditriskscoringenginejava.version.RuleVersionRegistry;
import org.springframework.stereotype.Component;

import java.time.ZoneOffset;

/**
 * 夜间交易因子：交易事件时间落在 UTC 00:00-05:00（不含 5 点）即按版本参数加分。
 */
@Component
public class NightTimeRule implements RiskRule {

    @Override
    public String code() {
        return RuleVersionRegistry.NIGHT_TIME;
    }

    @Override
    public String factor() {
        return "夜间交易";
    }

    @Override
    public FactorContribution evaluate(RuleContext ctx, RuleVersion version) {
        if (ctx.tx().eventTime() == null) {
            return null;
        }
        int hour = ctx.tx().eventTime().atOffset(ZoneOffset.UTC).getHour();
        if (hour >= 0 && hour < 5) {
            int points = version.intParam(code(), 10);
            return new FactorContribution(code(), factor(), points,
                    "交易时间 " + ctx.tx().eventTime() + " 落在夜间时段 00:00-05:00 UTC"
                            + "（版本 " + version.version() + "）");
        }
        return null;
    }
}
