package com.github.xingjianluo.creditriskscoringenginejava.tests;

import com.github.xingjianluo.creditriskscoringenginejava.caseflow.ReviewCaseService;
import com.github.xingjianluo.creditriskscoringenginejava.ledger.TransactionLedger;
import com.github.xingjianluo.creditriskscoringenginejava.model.RiskScoreResult;
import com.github.xingjianluo.creditriskscoringenginejava.model.RuleVersion;
import com.github.xingjianluo.creditriskscoringenginejava.model.Transaction;
import com.github.xingjianluo.creditriskscoringenginejava.service.RiskScoringService;
import com.github.xingjianluo.creditriskscoringenginejava.service.ScoringPipeline;
import com.github.xingjianluo.creditriskscoringenginejava.version.RuleVersionRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(FixedClockTestConfig.class)
class RuleVersionSwitchTest {

    private static final Logger log = LoggerFactory.getLogger(RuleVersionSwitchTest.class);

    @Autowired
    private RiskScoringService scoringService;
    @Autowired
    private ScoringPipeline pipeline;
    @Autowired
    private RuleVersionRegistry registry;
    @Autowired
    private TransactionLedger ledger;
    @Autowired
    private ReviewCaseService caseService;

    @Test
    @DisplayName("规则更新后新流水按新版本评分，历史结论不变，旧版本可读且可复现回溯")
    void versionSwitchKeepsHistoryAndAppliesNewVersion() {
        ledger.clear();
        caseService.clear();
        registry.activate("v1");

        // v1 下：金额 8000 为大额；阈值 10000
        Transaction txV1 = Transactions.builder("V-TX1", "B1")
                .amount("8000").time("2026-09-26T12:00:00Z").cp("CP-1").country("CN").build();
        RiskScoreResult v1Result = scoringService.submit(txV1).score();
        log.info("[UT-VERSION] 输入信号(tx={},amount={}) 在v1下 命中规则={} 判定依据=总分{}/等级{}/版本{}",
                txV1.transactionId(), txV1.amount(),
                v1Result.contributions().stream().map(c -> c.ruleCode() + "+" + c.points()).toList(),
                v1Result.totalScore(), v1Result.level(), v1Result.ruleVersion());
        assertThat(v1Result.ruleVersion()).isEqualTo("v1");
        assertThat(v1Result.contributions()).extracting(c -> c.ruleCode())
                .doesNotContain(RuleVersionRegistry.LARGE_AMOUNT);

        // 发布并切换 v2（大额阈值收紧到 5000）
        RuleVersion v2 = registry.activate("v2");
        assertThat(v2.highThreshold()).isLessThan(registry.get("v1").highThreshold());

        // 新流水按 v2 评分：同样 8000 在 v2 下命中大额
        Transaction txV2 = Transactions.builder("V-TX2", "B2")
                .amount("8000").time("2026-09-26T12:05:00Z").cp("CP-1").country("CN").build();
        RiskScoreResult v2Result = scoringService.submit(txV2).score();
        log.info("[UT-VERSION] 输入信号(tx={},amount={}) 在v2下 命中规则={} 判定依据=总分{}/等级{}/版本{}",
                txV2.transactionId(), txV2.amount(),
                v2Result.contributions().stream().map(c -> c.ruleCode() + "+" + c.points()).toList(),
                v2Result.totalScore(), v2Result.level(), v2Result.ruleVersion());
        assertThat(v2Result.ruleVersion()).isEqualTo("v2");
        assertThat(v2Result.contributions()).extracting(c -> c.ruleCode())
                .contains(RuleVersionRegistry.LARGE_AMOUNT);

        // 历史结论保持不变且可读
        RiskScoreResult history = scoringService.getScore("V-TX1").orElseThrow();
        assertThat(history).isEqualTo(v1Result);
        assertThat(history.ruleVersion()).isEqualTo("v1");
        log.info("[UT-VERSION] 历史结论复读 tx={} 版本={} 总分{}（未被v2改写）",
                history.transactionId(), history.ruleVersion(), history.totalScore());

        // 用旧版本对 TX2 复算：结果应与“若当时按 v1 评分”一致（不命中大额）
        RiskScoreResult recomputedV1 = pipeline.recomputeWithVersion(txV2, "v1");
        assertThat(recomputedV1.ruleVersion()).isEqualTo("v1");
        assertThat(recomputedV1.contributions()).extracting(c -> c.ruleCode())
                .doesNotContain(RuleVersionRegistry.LARGE_AMOUNT);
        // 用 v2 复算 TX1：可复现当时切换后的差异（命中大额）
        RiskScoreResult recomputedV2 = pipeline.recomputeWithVersion(txV1, "v2");
        assertThat(recomputedV2.contributions()).extracting(c -> c.ruleCode())
                .contains(RuleVersionRegistry.LARGE_AMOUNT);
        log.info("[UT-VERSION] 复算回溯: TX2@v1总分={}, TX1@v2总分={}（历史落库结论不受影响）",
                recomputedV1.totalScore(), recomputedV2.totalScore());

        // 旧版本仍可正常读取
        assertThat(registry.get("v1").version()).isEqualTo("v1");
        assertThat(registry.all()).extracting(RuleVersion::version).contains("v1", "v2");
    }
}
