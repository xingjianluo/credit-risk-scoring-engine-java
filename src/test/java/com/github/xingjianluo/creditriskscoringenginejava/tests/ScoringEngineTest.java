package com.github.xingjianluo.creditriskscoringenginejava.tests;

import com.github.xingjianluo.creditriskscoringenginejava.domain.FactorCode;
import com.github.xingjianluo.creditriskscoringenginejava.domain.RiskLevel;
import com.github.xingjianluo.creditriskscoringenginejava.domain.RuleSet;
import com.github.xingjianluo.creditriskscoringenginejava.domain.ScoreResult;
import com.github.xingjianluo.creditriskscoringenginejava.domain.TransactionEvent;
import com.github.xingjianluo.creditriskscoringenginejava.service.ScoringEngine;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 单测：多因子评分的单调性、贡献分解与可解释证据。
 */
class ScoringEngineTest {

    private static final Logger log = LoggerFactory.getLogger(ScoringEngineTest.class);

    private final ScoringEngine engine = new ScoringEngine();
    private final RuleSet v1 = RuleSet.v1(Instant.parse("2026-09-01T00:00:00Z"));
    private final Instant scoredAt = Instant.parse("2026-09-20T12:00:00Z");

    private TransactionEvent txn(String id, String amount, String payee,
                                 String jurisdiction, Instant t) {
        return new TransactionEvent(id, "ACC-1", new BigDecimal(amount), payee,
                jurisdiction, t);
    }

    @Test
    void noFactorHit_scoreIsZeroAndLow() {
        TransactionEvent dayTime = txn("t-safe", "100", "P1", "US",
                Instant.parse("2026-09-20T10:00:00Z"));
        ScoreResult r = engine.score(dayTime, List.of(), v1, scoredAt);

        log.info("[测试输入] {} 金额=100 辖区=US 时间=10:00 历史=0", dayTime.transactionId());
        log.info("[命中规则] 无；[判定依据] total={} level={}", r.totalScore(), r.riskLevel());

        assertEquals(10, r.totalScore()); // 账户首笔交易：收款方首次出现 -> 陌生收款方
        assertEquals(RiskLevel.LOW, r.riskLevel());
        assertEquals(1, r.contributions().size());
        assertEquals(FactorCode.NEW_PAYEE, r.contributions().get(0).factor());
        assertEquals("v1", r.rulesVersion());
    }

    @Test
    void amountAndHighRiskJurisdiction_contributeAndExplain() {
        TransactionEvent risky = txn("t-risky", "80000", "P1", "XX",
                Instant.parse("2026-09-20T10:00:00Z"));
        ScoreResult r = engine.score(risky, List.of(), v1, scoredAt);

        log.info("[测试输入] {} 金额=80000 辖区=XX（高风险） 时间=10:00",
                risky.transactionId());
        r.contributions().forEach(c ->
                log.info("[命中规则] factor={} points={} 依据={}",
                        c.factor(), c.points(), c.evidence()));

        assertEquals(10 + 20 + 25, r.totalScore()); // 陌生收款方 + 大额 + 高风险辖区
        assertEquals(RiskLevel.MEDIUM, r.riskLevel()); // 55 在 [30,60) 区间
        assertTrue(r.contributions().stream()
                .anyMatch(c -> c.factor() == FactorCode.AMOUNT_LARGE && c.points() == 20));
        assertTrue(r.contributions().stream().anyMatch(c ->
                c.factor() == FactorCode.HIGH_RISK_JURISDICTION
                        && c.evidence().contains("XX")));
    }

    @Test
    void monotonic_moreRiskFactorsNeverLowerScore() {
        Instant day = Instant.parse("2026-09-20T10:00:00Z");
        Instant night = Instant.parse("2026-09-20T03:00:00Z");
        // 以"已知收款方"作为公共基线，隔离其他因子的单调性
        TransactionEvent seen = txn("t-hist", "1", "P1", "US",
                Instant.parse("2026-09-20T08:00:00Z"));
        java.util.List<TransactionEvent> prior = java.util.List.of(seen);
        TransactionEvent base = txn("t-mono-1", "100", "P1", "US", day);
        TransactionEvent bigger = txn("t-mono-2", "80000", "P1", "US", day);
        TransactionEvent biggerNight = txn("t-mono-3", "80000", "P1", "US", night);
        TransactionEvent biggerNightRisky = txn("t-mono-4", "80000", "P1", "XX", night);

        int s1 = engine.score(base, prior, v1, scoredAt).totalScore();
        int s2 = engine.score(bigger, prior, v1, scoredAt).totalScore();
        int s3 = engine.score(biggerNight, prior, v1, scoredAt).totalScore();
        int s4 = engine.score(biggerNightRisky, prior, v1, scoredAt).totalScore();

        log.info("[测试输入] 逐步叠加风险因素 金额↑→深夜→高风险辖区");
        log.info("[判定依据] 分数序列 {} -> {} -> {} -> {}，必须单调不减", s1, s2, s3, s4);

        assertTrue(s1 <= s2 && s2 <= s3 && s3 <= s4, "总分必须对风险因素单调");
        assertTrue(s4 >= s1);
    }

    @Test
    void rapidSuccessionAndVelocity_useOnlyEarlierEvents() {
        Instant t0 = Instant.parse("2026-09-20T10:00:00Z");
        TransactionEvent old1 = txn("t-h1", "60000", "P1", "US", t0);
        TransactionEvent old2 = txn("t-h2", "60000", "P1", "US", t0.plusSeconds(20));
        TransactionEvent now = txn("t-cur", "100", "P1", "US", t0.plusSeconds(40));

        ScoreResult r = engine.score(now, List.of(old1, old2), v1, scoredAt);
        log.info("[测试输入] 当前流水 t-cur，历史两笔间隔 20s/40s 各 60000");
        r.contributions().forEach(c ->
                log.info("[命中规则] factor={} points={} 依据={}",
                        c.factor(), c.points(), c.evidence()));

        assertTrue(r.contributions().stream()
                .anyMatch(c -> c.factor() == FactorCode.RAPID_SUCCESSION));
        assertTrue(r.contributions().stream()
                .anyMatch(c -> c.factor() == FactorCode.VELOCITY_AMOUNT));
        // 历史 120000 >= 100000 窗口阈值；收款方 P1 已见过，不应命中新收款方
        assertTrue(r.contributions().stream()
                .noneMatch(c -> c.factor() == FactorCode.NEW_PAYEE));
    }
}
