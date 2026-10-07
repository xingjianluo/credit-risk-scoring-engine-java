package com.github.xingjianluo.creditriskscoringenginejava.tests;

import com.github.xingjianluo.creditriskscoringenginejava.caseflow.AccountCaseService;
import com.github.xingjianluo.creditriskscoringenginejava.caseflow.ReviewCaseService;
import com.github.xingjianluo.creditriskscoringenginejava.ledger.TransactionLedger;
import com.github.xingjianluo.creditriskscoringenginejava.model.AccountCase;
import com.github.xingjianluo.creditriskscoringenginejava.model.ArrivalMode;
import com.github.xingjianluo.creditriskscoringenginejava.model.BatchReport;
import com.github.xingjianluo.creditriskscoringenginejava.model.ReviewCase;
import com.github.xingjianluo.creditriskscoringenginejava.model.RiskScoreResult;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 账户级可疑活动归集：窗口归集开案、阈值、幂等、乱序/并发一致性、历史结论不可变。
 */
@SpringBootTest
@Import(FixedClockTestConfig.class)
class AccountAggregationTest {

    private static final Logger log = LoggerFactory.getLogger(AccountAggregationTest.class);

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

    /** 夜间 + 离岸 + 大额 + 新对手：v2 下 75 分 CRITICAL。 */
    private Transaction highRiskTx(String id, String account, String isoTime) {
        return Transactions.builder(id, account).amount("20000").time(isoTime)
                .cp("CP-" + id).country("KY").build();
    }

    @Test
    @DisplayName("窗口内 3 笔高风险流水归集到同一账户案件，历史评分与单笔案件审计不变")
    void windowAggregationOpensOneAccountCase() {
        scoringService.submit(highRiskTx("AGG-1", "ACC-1", "2026-09-26T02:00:00Z"));
        scoringService.submit(highRiskTx("AGG-2", "ACC-1", "2026-09-26T02:05:00Z"));
        assertThat(accountCaseService.all()).as("未达阈值不开账户案件").isEmpty();

        // 触发前记录历史结论与单笔案件审计快照
        RiskScoreResult s1 = ledger.scoreOf("AGG-1").orElseThrow();
        ReviewCase perTxCase1 = caseService.findByTransactionId("AGG-1").orElseThrow();
        int eventsBefore = perTxCase1.getEvents().size();

        scoringService.submit(highRiskTx("AGG-3", "ACC-1", "2026-09-26T02:10:00Z"));

        List<AccountCase> accountCases = accountCaseService.findByAccount("ACC-1");
        assertThat(accountCases).hasSize(1);
        AccountCase ac = accountCases.get(0);
        assertThat(ac.getTransactionIds()).containsExactlyInAnyOrder("AGG-1", "AGG-2", "AGG-3");
        assertThat(ac.getReviewCaseIds()).hasSize(3);
        log.info("[UT-AGG] 窗口归集 caseId={} 关联流水={} 单笔案件={} 审计事件={}",
                ac.getCaseId(), ac.getTransactionIds(), ac.getReviewCaseIds(),
                ac.getEvents().size());

        // 历史评分结论不被归集改动（同一不可变对象）
        assertThat(ledger.scoreOf("AGG-1")).containsSame(s1);
        // 单笔案件审计链不被归集改动
        assertThat(caseService.findByTransactionId("AGG-1").orElseThrow().getEvents())
                .as("单笔案件审计事件不被归集追加").hasSize(eventsBefore);
        // 单笔案件照常独立存在
        assertThat(caseService.all()).hasSize(3);
    }

    @Test
    @DisplayName("窗口内高风险笔数不足阈值不开账户案件；滑出窗口的流水不计入")
    void belowThresholdAndSlidingWindow() {
        scoringService.submit(highRiskTx("AGG-W1", "ACC-2", "2026-09-26T02:00:00Z"));
        scoringService.submit(highRiskTx("AGG-W2", "ACC-2", "2026-09-26T02:10:00Z"));
        assertThat(accountCaseService.findByAccount("ACC-2")).isEmpty();

        // 第三笔与第一笔间隔 40 分钟（滑出 30 分钟窗口），窗口内仅 2 笔，仍不开案
        scoringService.submit(highRiskTx("AGG-W3", "ACC-2", "2026-09-26T02:40:00Z"));
        assertThat(accountCaseService.findByAccount("ACC-2")).isEmpty();
        assertThat(caseService.all()).as("单笔案件照常开立").hasSize(3);
        log.info("[UT-AGG] 滑动窗口: 3 笔分散超窗，账户案件=0，单笔案件=3");
    }

    @Test
    @DisplayName("同批重复流水与重复提交不重复归集、不重复开案")
    void duplicatesDoNotDoubleAggregate() {
        Transaction t1 = highRiskTx("AGG-D1", "ACC-3", "2026-09-26T02:00:00Z");
        Transaction t2 = highRiskTx("AGG-D2", "ACC-3", "2026-09-26T02:05:00Z");
        Transaction t3 = highRiskTx("AGG-D3", "ACC-3", "2026-09-26T02:10:00Z");
        // 同批内 t1 重复 3 次
        scoringService.submitBatch(List.of(t1, t1, t2, t1, t3));
        assertThat(accountCaseService.findByAccount("ACC-3")).hasSize(1);
        assertThat(accountCaseService.findByAccount("ACC-3").get(0).getTransactionIds())
                .containsExactlyInAnyOrder("AGG-D1", "AGG-D2", "AGG-D3");

        // 整批原样再提交两次（幂等）
        scoringService.submitBatch(List.of(t1, t2, t3));
        scoringService.submit(t1);
        List<AccountCase> cases = accountCaseService.findByAccount("ACC-3");
        assertThat(cases).as("重复提交不重复开案").hasSize(1);
        assertThat(cases.get(0).getTransactionIds())
                .as("重复提交不重复归集").hasSize(3);
        assertThat(caseService.all()).as("单笔案件不重复开").hasSize(3);
        log.info("[UT-AGG] 幂等: 账户案件={} 关联流水={} 单笔案件=3",
                cases.get(0).getCaseId(), cases.get(0).getTransactionIds());
    }

    @Test
    @DisplayName("顺序/乱序/并发三种到达方式归集结果与案件计数一致")
    void arrivalModesConsistent() {
        List<Transaction> feed = List.of(
                highRiskTx("AGG-M1", "ACC-4", "2026-09-26T02:00:00Z"),
                highRiskTx("AGG-M2", "ACC-4", "2026-09-26T02:03:00Z"),
                highRiskTx("AGG-M3", "ACC-4", "2026-09-26T02:06:00Z"),
                highRiskTx("AGG-M4", "ACC-4", "2026-09-26T02:09:00Z"),
                // 重复流水混入
                highRiskTx("AGG-M2", "ACC-4", "2026-09-26T02:03:00Z"),
                highRiskTx("AGG-M2", "ACC-4", "2026-09-26T02:03:00Z"));

        BatchReport ordered = simulator.run("agg-ordered", feed, ArrivalMode.IN_ORDER);
        List<String> orderedLinked = accountCaseService.findByAccount("ACC-4").get(0)
                .getTransactionIds();
        BatchReport shuffled = simulator.run("agg-shuffled", feed, ArrivalMode.OUT_OF_ORDER);
        List<String> shuffledLinked = accountCaseService.findByAccount("ACC-4").get(0)
                .getTransactionIds();
        BatchReport concurrent = simulator.run("agg-concurrent", feed, ArrivalMode.CONCURRENT);
        List<String> concurrentLinked = accountCaseService.findByAccount("ACC-4").get(0)
                .getTransactionIds();

        log.info("[UT-AGG] 三模式账户归集案: 顺序={} 乱序={} 并发={}",
                ordered.accountCasesOpened(), shuffled.accountCasesOpened(),
                concurrent.accountCasesOpened());
        for (BatchReport r : List.of(ordered, shuffled, concurrent)) {
            assertThat(r.accountCasesOpened()).isEqualTo(1);
            assertThat(r.casesOpened()).isEqualTo(4);
            assertThat(r.accepted()).isEqualTo(6);
        }
        assertThat(shuffledLinked).containsExactlyInAnyOrderElementsOf(orderedLinked);
        assertThat(concurrentLinked).containsExactlyInAnyOrderElementsOf(orderedLinked);
        assertThat(orderedLinked).containsExactlyInAnyOrder(
                "AGG-M1", "AGG-M2", "AGG-M3", "AGG-M4");
    }

    @Test
    @DisplayName("并发提交同一账户多笔高风险流水：恰好一个账户案件且全部归集")
    void concurrentSubmitsSingleAccountCase() {
        int n = 8;
        CyclicBarrier barrier = new CyclicBarrier(n);
        List<CompletableFuture<Void>> futures = IntStream.rangeClosed(1, n)
                .mapToObj(i -> CompletableFuture.runAsync(() -> {
                    try {
                        barrier.await();
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                    scoringService.submit(highRiskTx("AGG-C" + i, "ACC-5",
                            "2026-09-26T02:00:00Z"));
                })).toList();
        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();

        List<AccountCase> cases = accountCaseService.findByAccount("ACC-5");
        assertThat(cases).as("并发下恰好一个账户案件").hasSize(1);
        assertThat(cases.get(0).getTransactionIds())
                .as("全部高风险流水都被归集").hasSize(n);
        assertThat(caseService.all()).hasSize(n);
        log.info("[UT-AGG] 并发: {} 线程同账户提交，账户案件={} 归集流水数={}",
                n, cases.get(0).getCaseId(), cases.get(0).getTransactionIds().size());
    }

    @Test
    @DisplayName("归集后既有评分结论逐字段不变（版本/分数/等级/证据）")
    void historicalConclusionsImmutable() {
        scoringService.submit(highRiskTx("AGG-H1", "ACC-6", "2026-09-26T02:00:00Z"));
        RiskScoreResult before = ledger.scoreOf("AGG-H1").orElseThrow();
        List<Object> contributionsBefore = new ArrayList<>(before.contributions());

        scoringService.submit(highRiskTx("AGG-H2", "ACC-6", "2026-09-26T02:05:00Z"));
        scoringService.submit(highRiskTx("AGG-H3", "ACC-6", "2026-09-26T02:10:00Z"));
        assertThat(accountCaseService.findByAccount("ACC-6")).hasSize(1);

        RiskScoreResult after = ledger.scoreOf("AGG-H1").orElseThrow();
        assertThat(after.totalScore()).isEqualTo(before.totalScore());
        assertThat(after.level()).isEqualTo(before.level());
        assertThat(after.ruleVersion()).isEqualTo(before.ruleVersion());
        assertThat(after.contributions()).isEqualTo(contributionsBefore);
        log.info("[UT-AGG] 历史结论不变: tx=AGG-H1 总分={} 等级={} 版本={} 命中={}",
                after.totalScore(), after.level(), after.ruleVersion(),
                after.contributions().stream().map(c -> c.ruleCode() + "+" + c.points()).toList());
    }
}
