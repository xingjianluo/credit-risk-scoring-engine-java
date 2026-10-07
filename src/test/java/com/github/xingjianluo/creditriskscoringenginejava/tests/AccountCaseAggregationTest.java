package com.github.xingjianluo.creditriskscoringenginejava.tests;

import com.github.xingjianluo.creditriskscoringenginejava.caseflow.AccountCaseService;
import com.github.xingjianluo.creditriskscoringenginejava.caseflow.ReviewCaseService;
import com.github.xingjianluo.creditriskscoringenginejava.ledger.TransactionLedger;
import com.github.xingjianluo.creditriskscoringenginejava.model.AccountCase;
import com.github.xingjianluo.creditriskscoringenginejava.model.ArrivalMode;
import com.github.xingjianluo.creditriskscoringenginejava.model.BatchReport;
import com.github.xingjianluo.creditriskscoringenginejava.model.CaseEvent;
import com.github.xingjianluo.creditriskscoringenginejava.model.ReviewCase;
import com.github.xingjianluo.creditriskscoringenginejava.model.RiskScoreResult;
import com.github.xingjianluo.creditriskscoringenginejava.model.SubmissionOutcome;
import com.github.xingjianluo.creditriskscoringenginejava.model.Transaction;
import com.github.xingjianluo.creditriskscoringenginejava.service.LocalEventSimulator;
import com.github.xingjianluo.creditriskscoringenginejava.service.RiskScoringService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 账户级可疑活动归集：同账户同一时间窗口内多笔高风险流水归集到同一账户案件，
 * 重复/乱序/并发下归集结果与计数一致，且不改动历史结论与单笔案件审计。
 */
@SpringBootTest
@Import(FixedClockTestConfig.class)
class AccountCaseAggregationTest {

    private static final Logger log = LoggerFactory.getLogger(AccountCaseAggregationTest.class);

    @Autowired
    private RiskScoringService scoringService;
    @Autowired
    private LocalEventSimulator simulator;
    @Autowired
    private TransactionLedger ledger;
    @Autowired
    private ReviewCaseService caseService;
    @Autowired
    private AccountCaseService accountCaseService;

    @BeforeEach
    void reset() {
        ledger.clear();
        caseService.clear();
        accountCaseService.clear();
    }

    /** 高风险样本：大额 6000 + 新对手 + 离岸 KY + 夜间 -> CRITICAL（v2 阈值 75）。 */
    private static Transaction highRisk(String id, String account, String isoTime, String cp) {
        return Transactions.builder(id, account).amount("6000")
                .time(isoTime).cp(cp).country("KY").build();
    }

    /** 同一小时桶（01:00-02:00）内的三笔高风险流水 + 桶外（02:30）一笔。 */
    private static List<Transaction> feed() {
        return List.of(
                highRisk("H1", "AGG1", "2026-09-26T01:05:00Z", "CP-A"),
                highRisk("H2", "AGG1", "2026-09-26T01:15:00Z", "CP-B"),
                highRisk("H3", "AGG1", "2026-09-26T01:25:00Z", "CP-C"),
                highRisk("H4", "AGG1", "2026-09-26T02:30:00Z", "CP-D"),
                // 同批重复流水：H2 再出现两次，不得重复归集/开案
                highRisk("H2", "AGG1", "2026-09-26T01:15:00Z", "CP-B"),
                highRisk("H2", "AGG1", "2026-09-26T01:15:00Z", "CP-B"));
    }

    @Test
    @DisplayName("同窗口多笔高风险归集到同一账户案件；窗口外开新案；单笔案件保持独立")
    void aggregatesHighRiskIntoAccountCasePerWindow() {
        List<SubmissionOutcome> outcomes = scoringService.submitBatch(feed());

        log.info("[UT-AGG] 逐笔结论={}", outcomes.stream()
                .map(o -> o.transactionId() + ":" + (o.accepted()
                        ? o.score().totalScore() + "/" + o.score().level() : "拒绝"))
                .toList());

        // 4 笔唯一高风险流水 -> 4 个独立单笔案件
        assertThat(caseService.all()).hasSize(4);
        // 账户维度：01 点桶 3 笔归集一案，02 点桶 1 笔另开一案
        List<AccountCase> accountCases = accountCaseService.all();
        assertThat(accountCases).hasSize(2);
        for (AccountCase ac : accountCases) {
            log.info("[UT-AGG] 账户案件={} 窗口=[{},{}) 已归集={} 明细={}",
                    ac.getCaseId(), ac.getWindowStart(), ac.getWindowEnd(),
                    ac.getLinkedCount(), ac.getLinkedTransactionIds());
        }
        AccountCase bucket1 = accountCaseService.findByTransactionId("H1").orElseThrow();
        assertThat(bucket1.getLinkedTransactionIds())
                .containsExactlyInAnyOrder("H1", "H2", "H3");
        assertThat(bucket1.getLinkedCount()).isEqualTo(3);
        assertThat(accountCaseService.findByTransactionId("H2").orElseThrow().getCaseId())
                .isEqualTo(bucket1.getCaseId());
        assertThat(accountCaseService.findByTransactionId("H3").orElseThrow().getCaseId())
                .isEqualTo(bucket1.getCaseId());

        AccountCase bucket2 = accountCaseService.findByTransactionId("H4").orElseThrow();
        assertThat(bucket2.getCaseId()).isNotEqualTo(bucket1.getCaseId());
        assertThat(bucket2.getLinkedTransactionIds()).containsExactly("H4");

        // 重复流水 H2 只归集一次（计数不含重复）
        assertThat(bucket1.getLinkedCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("重复提交与乱序/并发到达下归集结果与案件计数一致")
    void aggregationConsistentAcrossArrivalModes() {
        List<Transaction> events = feed();

        BatchReport ordered = simulator.run("agg-ordered", events, ArrivalMode.IN_ORDER);
        Map<String, String> orderedLinks = snapshotLinks();
        BatchReport shuffled = simulator.run("agg-shuffled", events, ArrivalMode.OUT_OF_ORDER);
        Map<String, String> shuffledLinks = snapshotLinks();
        BatchReport concurrent = simulator.run("agg-concurrent", events, ArrivalMode.CONCURRENT);
        Map<String, String> concurrentLinks = snapshotLinks();

        log.info("[UT-AGG] 顺序: 开案={} 账户归集案={}", ordered.casesOpened(), ordered.accountCasesOpened());
        log.info("[UT-AGG] 乱序: 开案={} 账户归集案={}", shuffled.casesOpened(), shuffled.accountCasesOpened());
        log.info("[UT-AGG] 并发: 开案={} 账户归集案={}", concurrent.casesOpened(), concurrent.accountCasesOpened());
        log.info("[UT-AGG] 归集映射 顺序={} 乱序={} 并发={}", orderedLinks, shuffledLinks, concurrentLinks);

        for (BatchReport r : List.of(ordered, shuffled, concurrent)) {
            assertThat(r.casesOpened()).isEqualTo(4);
            assertThat(r.accountCasesOpened()).isEqualTo(2);
        }
        // 归集映射（tx -> 账户案件窗口）跨到达方式一致：按窗口起点比较，案件编号也一致（序列确定性）
        assertThat(shuffledLinks).isEqualTo(orderedLinks);
        assertThat(concurrentLinks).isEqualTo(orderedLinks);

        // 并发后再重复提交 H2：幂等返回，不新增归集
        SubmissionOutcome again = scoringService.submit(
                highRisk("H2", "AGG1", "2026-09-26T01:15:00Z", "CP-B"));
        assertThat(again.duplicate()).isTrue();
        assertThat(accountCaseService.all()).hasSize(2);
        assertThat(accountCaseService.findByTransactionId("H2").orElseThrow().getLinkedCount())
                .isEqualTo(3);
    }

    @Test
    @DisplayName("归集不改动历史评分结论与单笔案件审计信息")
    void aggregationDoesNotMutateHistory() {
        scoringService.submitBatch(feed());

        Map<String, RiskScoreResult> scoresBefore = ledger.allScores().stream()
                .collect(Collectors.toMap(RiskScoreResult::transactionId, Function.identity()));
        Map<String, List<CaseEvent>> caseEventsBefore = caseService.all().stream()
                .collect(Collectors.toMap(ReviewCase::getCaseId, ReviewCase::getEvents));

        // 触发更多归集活动：重复提交全部 + 新增同窗口高风险流水
        scoringService.submitBatch(feed());
        scoringService.submit(highRisk("H5", "AGG1", "2026-09-26T01:45:00Z", "CP-E"));

        Map<String, RiskScoreResult> scoresAfter = ledger.allScores().stream()
                .collect(Collectors.toMap(RiskScoreResult::transactionId, Function.identity()));
        Map<String, List<CaseEvent>> caseEventsAfter = caseService.all().stream()
                .collect(Collectors.toMap(ReviewCase::getCaseId, ReviewCase::getEvents));

        // 历史评分结论逐笔不变
        for (Map.Entry<String, RiskScoreResult> e : scoresBefore.entrySet()) {
            assertThat(scoresAfter.get(e.getKey()))
                    .as("历史结论不可变: %s", e.getKey())
                    .isEqualTo(e.getValue());
        }
        // 既有单笔案件的审计事件链不变（新案件 H5 允许新增）
        for (Map.Entry<String, List<CaseEvent>> e : caseEventsBefore.entrySet()) {
            assertThat(caseEventsAfter.get(e.getKey()))
                    .as("单笔案件审计不可变: %s", e.getKey())
                    .isEqualTo(e.getValue());
        }
        // H5 归集到 01 点桶既有账户案件
        AccountCase bucket1 = accountCaseService.findByTransactionId("H5").orElseThrow();
        assertThat(bucket1.getLinkedTransactionIds())
                .containsExactlyInAnyOrder("H1", "H2", "H3", "H5");
        assertThat(accountCaseService.all()).hasSize(2);
        log.info("[UT-AGG] 历史结论 {} 笔与 {} 个单笔案件审计均未被归集改动",
                scoresBefore.size(), caseEventsBefore.size());
    }

    private Map<String, String> snapshotLinks() {
        return accountCaseService.all().stream()
                .flatMap(ac -> ac.getLinkedTransactionIds().stream()
                        .map(txId -> Map.entry(txId, ac.getWindowStart().toString())))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }
}
