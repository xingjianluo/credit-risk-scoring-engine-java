package com.github.xingjianluo.creditriskscoringenginejava.rule;

import com.github.xingjianluo.creditriskscoringenginejava.model.FactorContribution;
import com.github.xingjianluo.creditriskscoringenginejava.model.RiskLevel;
import com.github.xingjianluo.creditriskscoringenginejava.model.RuleContext;
import com.github.xingjianluo.creditriskscoringenginejava.model.RuleVersion;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 多因子规则引擎：汇总各因子非负贡献，按版本阈值定级。
 *
 * <p>单调性：每个因子只贡献非负分，风险因素从无到有只会加分，
 * 各版本阈值固定，因此总分与等级对风险因素单调（因素增多不会降分）。
 */
@Component
public class RiskRuleEngine {

    private final List<RiskRule> rules;

    public RiskRuleEngine(List<RiskRule> rules) {
        // 按规则码排序，保证贡献列表顺序稳定、输出可复现
        this.rules = rules.stream().sorted(java.util.Comparator.comparing(RiskRule::code)).toList();
    }

    public List<FactorContribution> evaluateAll(RuleContext ctx, RuleVersion version) {
        return rules.stream()
                .map(rule -> rule.evaluate(ctx, version))
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    public int sum(List<FactorContribution> contributions) {
        return contributions.stream().mapToInt(FactorContribution::points).sum();
    }

    public RiskLevel level(int score, RuleVersion version) {
        return version.levelOf(score);
    }
}
