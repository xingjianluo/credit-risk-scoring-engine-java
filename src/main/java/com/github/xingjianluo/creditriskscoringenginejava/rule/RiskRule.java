package com.github.xingjianluo.creditriskscoringenginejava.rule;

import com.github.xingjianluo.creditriskscoringenginejava.model.FactorContribution;
import com.github.xingjianluo.creditriskscoringenginejava.model.RuleContext;
import com.github.xingjianluo.creditriskscoringenginejava.model.RuleVersion;

/**
 * 风险因子规则。各因子贡献非负，保证评分对风险因素单调。
 */
public interface RiskRule {

    /** 规则码，同时是阈值参数的前缀/主键。 */
    String code();

    /** 因子名称（用于解释输出）。 */
    String factor();

    /** 按指定版本阈值求值；未命中返回 null。 */
    FactorContribution evaluate(RuleContext ctx, RuleVersion version);
}
