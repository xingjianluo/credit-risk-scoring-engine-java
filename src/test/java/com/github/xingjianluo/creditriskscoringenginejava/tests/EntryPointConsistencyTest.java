package com.github.xingjianluo.creditriskscoringenginejava.tests;

import com.github.xingjianluo.creditriskscoringenginejava.caseflow.AccountCaseService;
import com.github.xingjianluo.creditriskscoringenginejava.caseflow.ReviewCaseService;
import com.github.xingjianluo.creditriskscoringenginejava.ledger.TransactionLedger;
import com.github.xingjianluo.creditriskscoringenginejava.model.ArrivalMode;
import com.github.xingjianluo.creditriskscoringenginejava.model.BatchReport;
import com.github.xingjianluo.creditriskscoringenginejava.model.RejectionReason;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 统一受理入口契约：同一笔异常流水走单笔/批量/模拟回放/并发批量，
 * 处理结果、可区分拒绝原因与留痕一致；异常输入不影响同批正常流水。
 */
@SpringBootTest
@Import(FixedClockTestConfig.class)
class EntryPointConsistencyTest {

    private static final Logger log = LoggerFactory.getLogger(EntryPointConsistencyTest.class);

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

    private Transaction negativeAmountTx() {
        return Transactions.builder("EP-BAD-1", "EP-ACC").amount("-10")
                .time("2026-09-26T02:00:00Z").build();
    }

    @Test
    @DisplayName("同一笔非法流水：单笔/批量/模拟回放拒绝原因与留痕一致")
    void sameInvalidTxAcrossEntries() {
        // 1) 单笔入口
        SubmissionOutcome single = scoringService.submit(negativeAmountTx());
        assertThat(single.accepted()).isFalse();
        assertThat(single.rejection().reason()).isEqualTo(RejectionReason.NON_POSITIVE_AMOUNT);
        assertThat(ledger.allRejections()).hasSize(1);
        String fingerprint = single.rejection().fingerprint();
        log.info("[UT-ENTRY] 单笔入口: reason={} fingerprint={}",
                single.rejection().reason(), fingerprint);

        // 2) 批量入口
        reset();
        List<SubmissionOutcome> batch = scoringService.submitBatch(List.of(negativeAmountTx()));
        assertThat(batch).hasSize(1);
        assertThat(batch.get(0).accepted()).isFalse();
        assertThat(batch.get(0).rejection().reason()).isEqualTo(RejectionReason.NON_POSITIVE_AMOUNT);
        assertThat(batch.get(0).rejection().fingerprint()).isEqualTo(fingerprint);
        assertThat(ledger.allRejections()).hasSize(1);
        log.info("[UT-ENTRY] 批量入口: reason={} fingerprint={} 留痕=1",
                batch.get(0).rejection().reason(), batch.get(0).rejection().fingerprint());

        // 3) 本地模拟回放（三种到达方式）
        for (ArrivalMode mode : ArrivalMode.values()) {
            BatchReport report = simulator.run("ep-" + mode, List.of(negativeAmountTx()), mode);
            assertThat(report.accepted()).isZero();
            assertThat(report.rejected()).isEqualTo(1);
            SubmissionOutcome o = report.outcomes().get(0);
            assertThat(o.accepted()).isFalse();
            assertThat(o.rejection().reason()).isEqualTo(RejectionReason.NON_POSITIVE_AMOUNT);
            assertThat(o.rejection().fingerprint()).isEqualTo(fingerprint);
            assertThat(ledger.allRejections()).hasSize(1);
            log.info("[UT-ENTRY] 模拟回放 {}: reason={} fingerprint={} 留痕={}",
                    mode, o.rejection().reason(), o.rejection().fingerprint(),
                    ledger.allRejections().size());
        }
    }

    @Test
    @DisplayName("空流水/空 ID：各入口给出可区分拒绝而不抛系统错误、不静默丢弃")
    void nullAndBlankIdInputs() {
        // null 流水（单笔入口）
        SubmissionOutcome o1 = scoringService.submit(null);
        assertThat(o1.accepted()).isFalse();
        assertThat(o1.rejection().reason()).isEqualTo(RejectionReason.MISSING_TRANSACTION_ID);
        // 空 ID 流水（批量入口，与正常流水混合）
        Transaction blankId = Transactions.builder("  ", "EP-ACC").build();
        Transaction ok = Transactions.builder("EP-OK-1", "EP-ACC").build();
        List<SubmissionOutcome> results = scoringService.submitBatch(java.util.Arrays.asList(blankId, ok, null));
        assertThat(results).hasSize(3);
        assertThat(results.get(0).accepted()).isFalse();
        assertThat(results.get(0).rejection().reason()).isEqualTo(RejectionReason.MISSING_TRANSACTION_ID);
        assertThat(results.get(1).accepted()).isTrue();
        assertThat(results.get(2).accepted()).isFalse();
        // 留痕：null 流水与空 ID 流水各一条（完全相同的非法输入按指纹幂等去重），正常流水被评分
        assertThat(ledger.allRejections()).hasSize(2);
        assertThat(ledger.allScores()).hasSize(1);
        log.info("[UT-ENTRY] 空流水/空ID: 拒绝留痕={}（相同非法输入按指纹去重）正常评分={}，无系统级错误、无静默丢弃",
                ledger.allRejections().size(), ledger.allScores().size());
    }

    @Test
    @DisplayName("异常输入不影响同批正常流水的评分与计数")
    void invalidInputDoesNotAffectBatchMates() {
        Transaction v1 = Transactions.builder("EP-V1", "EP-ACC2").amount("800")
                .time("2026-09-26T02:00:00Z").cp("CP-1").build();
        Transaction v2 = Transactions.builder("EP-V2", "EP-ACC2").amount("900")
                .time("2026-09-26T02:05:00Z").cp("CP-1").build();
        // 参照组：只含正常流水
        List<SubmissionOutcome> reference = scoringService.submitBatch(List.of(v1, v2));
        int score1 = reference.get(0).score().totalScore();
        int score2 = reference.get(1).score().totalScore();

        // 实验组：混入多笔异常流水（负金额/缺币种/未来时间/缺对手/空对象）
        reset();
        Transaction bad1 = Transactions.builder("EP-B1", "EP-ACC2").amount("-5")
                .time("2026-09-26T01:00:00Z").build();
        Transaction bad2 = Transactions.builder("EP-B2", "EP-ACC2").currency(null)
                .time("2026-09-26T01:30:00Z").build();
        Transaction bad3 = Transactions.builder("EP-B3", "EP-ACC2")
                .time("2099-01-01T00:00:00Z").build();
        Transaction bad4 = Transactions.builder("EP-B4", "EP-ACC2").cp(null)
                .time("2026-09-26T02:02:00Z").build();
        List<SubmissionOutcome> mixed = scoringService.submitBatch(
                java.util.Arrays.asList(bad1, v1, bad2, bad3, v2, bad4, null));

        assertThat(mixed).hasSize(7);
        assertThat(mixed.get(1).accepted()).isTrue();
        assertThat(mixed.get(1).score().totalScore()).isEqualTo(score1);
        assertThat(mixed.get(4).accepted()).isTrue();
        assertThat(mixed.get(4).score().totalScore()).isEqualTo(score2);
        assertThat(mixed.get(0).rejection().reason()).isEqualTo(RejectionReason.NON_POSITIVE_AMOUNT);
        assertThat(mixed.get(2).rejection().reason()).isEqualTo(RejectionReason.MISSING_CURRENCY);
        assertThat(mixed.get(3).rejection().reason()).isEqualTo(RejectionReason.FUTURE_EVENT_TIME);
        assertThat(mixed.get(5).rejection().reason()).isEqualTo(RejectionReason.MISSING_COUNTERPARTY);
        assertThat(mixed.get(6).rejection().reason()).isEqualTo(RejectionReason.MISSING_TRANSACTION_ID);
        assertThat(ledger.allScores()).hasSize(2);
        assertThat(ledger.allRejections()).hasSize(5);
        log.info("[UT-ENTRY] 混合批次: 正常流水分数 {}/{} 与参照组一致，异常留痕=5",
                score1, score2);
    }

    @Test
    @DisplayName("并发批量入口：同一非法流水并发提交只留痕一次且原因一致")
    void concurrentInvalidSubmission() throws Exception {
        int threads = 8;
        java.util.concurrent.CyclicBarrier barrier = new java.util.concurrent.CyclicBarrier(threads);
        List<java.util.concurrent.CompletableFuture<SubmissionOutcome>> futures =
                java.util.stream.IntStream.range(0, threads)
                        .mapToObj(i -> java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                            try {
                                barrier.await();
                            } catch (Exception e) {
                                throw new IllegalStateException(e);
                            }
                            return scoringService.submit(negativeAmountTx());
                        })).toList();
        List<SubmissionOutcome> outcomes = futures.stream()
                .map(java.util.concurrent.CompletableFuture::join).toList();
        assertThat(outcomes).allSatisfy(o -> {
            assertThat(o.accepted()).isFalse();
            assertThat(o.rejection().reason()).isEqualTo(RejectionReason.NON_POSITIVE_AMOUNT);
        });
        assertThat(ledger.allRejections()).as("并发同一非法流水只留痕一次").hasSize(1);
        log.info("[UT-ENTRY] 并发非法提交: {} 线程结果一致，留痕=1", threads);
    }
}
