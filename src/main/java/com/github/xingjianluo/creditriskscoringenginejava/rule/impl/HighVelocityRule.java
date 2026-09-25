package com.github.xingjianluo.creditriskscoringenginejava.rule.impl;

import com.github.xingjianluo.creditriskscoringenginejava.baseline.AccountBaselineService;
import com.github.xingjianluo.creditriskscoringenginejava.model.FactorContribution;
import com.github.xingjianluo.creditriskscoringenginejava.model.RuleContext;
import com.github.xingjianluo.creditriskscoringenginejava.model.RuleVersion;
import com.github.xingjianluo.creditriskscoringenginejava.rule.RiskRule;
import com.github.xingjianluo.creditriskscoringenginejava.version.RuleVersionRegistry;
import org.springframework.stereotype.Component;

/**
 * 短时高频因子：评分时刻前 10 分钟窗口内同账户历史笔数达到版本阈值即加 20 分。
 */
@Component
public class HighVelocityRule implements RiskRule {

    public static final int POINTS = 20;

    @Override
    public String code() {
        return RuleVersionRegistry.HIGH_VELOCITY;
    }

    @Override
    public String factor() {
        return "短时高频";
    }

    @Override
    public FactorContribution evaluate(RuleContext ctx, RuleVersion version) {
        int threshold = version.intParam(code(), 3);
        int recent = ctx.baseline() == null ? 0 : ctx.baseline().recentWindowCount();
        if (recent >= threshold) {
            return new FactorContribution(code(), factor(), POINTS,
                    "前 " + AccountBaselineService.VELOCITY_WINDOW.toMinutes()
                            + " 分钟窗口内已有 " + recent + " 笔 >= 阈值 " + threshold
                            + "（版本 " + version.version() + "）");
        }
        return null;
    }
}
