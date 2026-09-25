package com.github.xingjianluo.creditriskscoringenginejava.rule.impl;

import com.github.xingjianluo.creditriskscoringenginejava.model.FactorContribution;
import com.github.xingjianluo.creditriskscoringenginejava.model.RuleContext;
import com.github.xingjianluo.creditriskscoringenginejava.model.RuleVersion;
import com.github.xingjianluo.creditriskscoringenginejava.rule.RiskRule;
import com.github.xingjianluo.creditriskscoringenginejava.version.RuleVersionRegistry;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 离岸/高风险地区因子：对手国家或地区命中高风险集合即按版本参数加分。
 */
@Component
public class OffshoreRule implements RiskRule {

    /** 本地内置的高风险国家/地区代码（演示用）。 */
    public static final Set<String> HIGH_RISK_JURISDICTIONS = Set.of("KY", "PA", "VG", "KH", "MM");

    @Override
    public String code() {
        return RuleVersionRegistry.OFFSHORE;
    }

    @Override
    public String factor() {
        return "离岸高风险地区";
    }

    @Override
    public FactorContribution evaluate(RuleContext ctx, RuleVersion version) {
        String country = ctx.tx().counterpartyCountry();
        if (country != null && HIGH_RISK_JURISDICTIONS.contains(country.toUpperCase())) {
            int points = version.intParam(code(), 20);
            return new FactorContribution(code(), factor(), points,
                    "对手国家/地区 " + country + " 命中高风险集合 " + HIGH_RISK_JURISDICTIONS
                            + "（版本 " + version.version() + "）");
        }
        return null;
    }
}
