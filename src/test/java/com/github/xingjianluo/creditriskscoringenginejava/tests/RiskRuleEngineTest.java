package com.github.xingjianluo.creditriskscoringenginejava.tests;

import com.github.xingjianluo.creditriskscoringenginejava.model.BaselineSnapshot;
import com.github.xingjianluo.creditriskscoringenginejava.model.FactorContribution;
import com.github.xingjianluo.creditriskscoringenginejava.model.RiskLevel;
import com.github.xingjianluo.creditriskscoringenginejava.model.RuleContext;
import com.github.xingjianluo.creditriskscoringenginejava.model.RuleVersion;
import com.github.xingjianluo.creditriskscoringenginejava.model.Transaction;
import com.github.xingjianluo.creditriskscoringenginejava.rule.RiskRuleEngine;
import com.github.xingjianluo.creditriskscoringenginejava.rule.impl.HighVelocityRule;
import com.github.xingjianluo.creditriskscoringenginejava.rule.impl.LargeAmountRule;
import com.github.xingjianluo.creditriskscoringenginejava.rule.impl.NewCounterpartyRule;
import com.github.xingjianluo.creditriskscoringenginejava.rule.impl.NightTimeRule;
import com.github.xingjianluo.creditriskscoringenginejava.rule.impl.OffshoreRule;
import com.github.xingjianluo.creditriskscoringenginejava.rule.impl.UnusualAmountRule;
import com.github.xingjianluo.creditriskscoringenginejava.version.RuleVersionRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class RiskRuleEngineTest {

    private static final Logger log = LoggerFactory.getLogger(RiskRuleEngineTest.class);

    private final RiskRuleEngine engine = new RiskRuleEngine(List.of(
            new LargeAmountRule(), new NewCounterpartyRule(), new HighVelocityRule(),
            new OffshoreRule(), new NightTimeRule(), new UnusualAmountRule()));
    private final RuleVersion v2 = new RuleVersionRegistry().get("v2");

    private BaselineSnapshot baseline(int prior, String avg, int window, Set<String> cps) {
        return new BaselineSnapshot("A1", Transactions.BASE, prior, avg, window, cps);
    }

    @Test
    @DisplayName("风险因素逐项加入时总分单调不减，每个因子贡献非负且可解释")
    void scoreIsMonotonicAndExplainable() {
        Transaction safe = Transactions.builder("M1", "A1")
                .amount("100").time("2026-09-26T12:00:00Z").cp("CP-KNOWN").country("CN").build();
        RuleContext safeCtx = new RuleContext(safe, baseline(5, "100", 0, Set.of("CP-KNOWN")));
        int prev = scoreAndLog("低风险基线", safeCtx);

        Transaction large = Transactions.builder("M2", "A1")
                .amount("6000").time("2026-09-26T12:00:00Z").cp("CP-KNOWN").country("CN").build();
        int afterLarge = scoreAndLog("叠加大额", new RuleContext(large,
                baseline(5, "100", 0, Set.of("CP-KNOWN"))));

        Transaction largeOffshore = Transactions.builder("M3", "A1")
                .amount("6000").time("2026-09-26T12:00:00Z").cp("CP-KNOWN").country("KY").build();
        int afterOffshore = scoreAndLog("再叠加离岸", new RuleContext(largeOffshore,
                baseline(5, "100", 0, Set.of("CP-KNOWN"))));

        Transaction night = Transactions.builder("M4", "A1")
                .amount("6000").time("2026-09-26T02:00:00Z").cp("CP-KNOWN").country("KY").build();
        int afterNight = scoreAndLog("再叠加夜间", new RuleContext(night,
                baseline(5, "100", 0, Set.of("CP-KNOWN"))));

        Transaction velocity = Transactions.builder("M5", "A1")
                .amount("6000").time("2026-09-26T02:00:00Z").cp("CP-KNOWN").country("KY").build();
        int afterVelocity = scoreAndLog("再叠加高频", new RuleContext(velocity,
                baseline(5, "100", 2, Set.of("CP-KNOWN"))));

        assertThat(prev).as("低风险基线应为0分").isZero();
        assertThat(afterLarge).isGreaterThan(prev);
        assertThat(afterOffshore).isGreaterThanOrEqualTo(afterLarge);
        assertThat(afterNight).isGreaterThanOrEqualTo(afterOffshore);
        assertThat(afterVelocity).isGreaterThanOrEqualTo(afterNight);
        assertThat(engine.level(afterVelocity, v2)).isEqualTo(RiskLevel.CRITICAL);
    }

    @Test
    @DisplayName("每个命中因子都带有规则码/分值/证据，分值之和等于总分")
    void contributionsAreExplainableAndConsistent() {
        Transaction tx = Transactions.builder("E1", "A1")
                .amount("9000").time("2026-09-26T03:00:00Z").cp("CP-NEW").country("PA").build();
        RuleContext ctx = new RuleContext(tx, baseline(3, "500", 2, Set.of("CP-OLD")));
        List<FactorContribution> hits = engine.evaluateAll(ctx, v2);
        int total = engine.sum(hits);

        log.info("[UT-EXPLAIN] tx={} 输入信号(amount={},time={},cp={},country={}) 命中规则明细:",
                tx.transactionId(), tx.amount(), tx.eventTime(), tx.counterpartyAccount(),
                tx.counterpartyCountry());
        for (FactorContribution c : hits) {
            log.info("[UT-EXPLAIN]   因子={} 规则码={} 贡献={} 证据={}",
                    c.factor(), c.ruleCode(), c.points(), c.evidence());
            assertThat(c.points()).isPositive();
            assertThat(c.evidence()).isNotBlank();
        }
        log.info("[UT-EXPLAIN] 判定依据=总分{} 等级{} 版本{} 阈值(M>=30,H>={},C>={})",
                total, engine.level(total, v2), v2.version(),
                v2.highThreshold(), v2.criticalThreshold());

        assertThat(hits.stream().mapToInt(FactorContribution::points).sum()).isEqualTo(total);
        assertThat(hits).extracting(FactorContribution::ruleCode)
                .contains(RuleVersionRegistry.LARGE_AMOUNT, RuleVersionRegistry.NEW_COUNTERPARTY,
                        RuleVersionRegistry.HIGH_VELOCITY, RuleVersionRegistry.OFFSHORE,
                        RuleVersionRegistry.NIGHT_TIME, RuleVersionRegistry.UNUSUAL_AMOUNT);
    }

    private int scoreAndLog(String label, RuleContext ctx) {
        List<FactorContribution> hits = engine.evaluateAll(ctx, v2);
        int total = engine.sum(hits);
        log.info("[UT-MONO] 场景={} tx={} 输入信号(amount={},time={},cp={},country={}) 命中规则={} 判定依据=总分{}/等级{}",
                label, ctx.tx().transactionId(), ctx.tx().amount(), ctx.tx().eventTime(),
                ctx.tx().counterpartyAccount(), ctx.tx().counterpartyCountry(),
                hits.stream().map(c -> c.ruleCode() + "+" + c.points()).toList(),
                total, engine.level(total, v2));
        return total;
    }
}
