package com.github.xingjianluo.creditriskscoringenginejava.tests;

import com.github.xingjianluo.creditriskscoringenginejava.caseflow.ReviewCaseService;
import com.github.xingjianluo.creditriskscoringenginejava.ledger.TransactionLedger;
import com.github.xingjianluo.creditriskscoringenginejava.model.ArrivalMode;
import com.github.xingjianluo.creditriskscoringenginejava.model.BatchReport;
import com.github.xingjianluo.creditriskscoringenginejava.model.RejectionRecord;
import com.github.xingjianluo.creditriskscoringenginejava.model.RejectionReason;
import com.github.xingjianluo.creditriskscoringenginejava.model.RiskScoreResult;
import com.github.xingjianluo.creditriskscoringenginejava.model.SubmissionOutcome;
import com.github.xingjianluo.creditriskscoringenginejava.model.Transaction;
import com.github.xingjianluo.creditriskscoringenginejava.service.LocalEventSimulator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(FixedClockTestConfig.class)
class ArrivalConsistencyTest {

    private static final Logger log = LoggerFactory.getLogger(ArrivalConsistencyTest.class);

    @Autowired
    private LocalEventSimulator simulator;
    @Autowired
    private TransactionLedger ledger;
    @Autowired
    private ReviewCaseService caseService;
    @Autowired
    private com.github.xingjianluo.creditriskscoringenginejava.service.RiskScoringService scoringService;

    private List<Transaction> sampleFeed() {
        return List.of(
                Transactions.builder("F1", "A1").amount("500").time("2026-09-26T01:00:00Z").cp("CP-1").country("CN").build(),
                Transactions.builder("F2", "A1").amount("700").time("2026-09-26T01:03:00Z").cp("CP-1").country("CN").build(),
                Transactions.builder("F3", "A1").amount("6000").time("2026-09-26T01:06:00Z").cp("CP-9").country("KY").build(),
                Transactions.builder("F4", "A1").amount("800").time("2026-09-26T01:09:00Z").cp("CP-1").country("CN").build(),
                Transactions.builder("F5", "A1").amount("12000").time("2026-09-26T02:30:00Z").cp("CP-9").country("KY").build(),
                Transactions.builder("F6", "A1").amount("600").time("2026-09-26T03:00:00Z").cp("CP-2").country("CN").build(),
                // 重复提交：F3 再来两次
                Transactions.builder("F3", "A1").amount("6000").time("2026-09-26T01:06:00Z").cp("CP-9").country("KY").build(),
                Transactions.builder("F3", "A1").amount("6000").time("2026-09-26T01:06:00Z").cp("CP-9").country("KY").build(),
                // 非法流水（负金额），重复出现两次也只能留痕一次
                Transactions.builder("X1", "A2").amount("-10").time("2026-09-26T01:00:00Z").cp("CP-1").country("CN").build(),
                Transactions.builder("X1", "A2").amount("-10").time("2026-09-26T01:00:00Z").cp("CP-1").country("CN").build(),
                // 非法流水：缺交易对手
                Transactions.builder("X2", "A2").amount("500").time("2026-09-26T01:00:00Z").cp(null).country("CN").build()
        );
    }

    @Test
    @DisplayName("顺序/乱序/并发三种到达方式下计数与逐笔评分完全一致，重复不重复计分/开案")
    void threeArrivalModesProduceIdenticalResults() {
        List<Transaction> feed = sampleFeed();

        BatchReport ordered = simulator.run("c-ordered", feed, ArrivalMode.IN_ORDER);
        Map<String, RiskScoreResult> orderedScores = snapshotScores();
        Map<String, RejectionReason> orderedRejections = snapshotRejections();
        long orderedCases = caseService.all().size();

        BatchReport shuffled = simulator.run("c-shuffled", feed, ArrivalMode.OUT_OF_ORDER);
        Map<String, RiskScoreResult> shuffledScores = snapshotScores();
        Map<String, RejectionReason> shuffledRejections = snapshotRejections();
        long shuffledCases = caseService.all().size();

        BatchReport concurrent = simulator.run("c-concurrent", feed, ArrivalMode.CONCURRENT);
        Map<String, RiskScoreResult> concurrentScores = snapshotScores();
        Map<String, RejectionReason> concurrentRejections = snapshotRejections();
        long concurrentCases = caseService.all().size();

        logReport("顺序", ordered);
        logReport("乱序", shuffled);
        logReport("并发", concurrent);
        log.info("[UT-ARRIVE] 逐笔评分顺序={}", summarizeScores(orderedScores));
        log.info("[UT-ARRIVE] 逐笔评分乱序={}", summarizeScores(shuffledScores));
        log.info("[UT-ARRIVE] 逐笔评分并发={}", summarizeScores(concurrentScores));

        // 计数一致：6 笔唯一合法 -> accepted=8(含2次F3重复)，拒绝=3(X1两次+X2一次)，重复=3，开案2(F3,F5)
        for (BatchReport r : List.of(ordered, shuffled, concurrent)) {
            assertThat(r.uniqueTransactions()).isEqualTo(8);
            assertThat(r.accepted()).isEqualTo(8);
            assertThat(r.rejected()).isEqualTo(3);
            assertThat(r.duplicates()).isEqualTo(3);
            assertThat(r.casesOpened()).isEqualTo(2);
        }
        // 逐笔评分与拒绝原因跨方式完全一致
        assertThat(shuffledScores).isEqualTo(orderedScores);
        assertThat(concurrentScores).isEqualTo(orderedScores);
        assertThat(shuffledRejections).isEqualTo(orderedRejections);
        assertThat(concurrentRejections).isEqualTo(orderedRejections);
        assertThat(shuffledCases).isEqualTo(orderedCases);
        assertThat(concurrentCases).isEqualTo(orderedCases);

        // 拒绝原因可区分
        assertThat(orderedRejections).containsEntry("X1", RejectionReason.NON_POSITIVE_AMOUNT);
        assertThat(orderedRejections).containsEntry("X2", RejectionReason.MISSING_COUNTERPARTY);

        // 账本中评分记录只按唯一交易保存（不重复计分）
        assertThat(ledger.allScores()).hasSize(6);
        assertThat(ledger.allRejections()).hasSize(2);

        // F3 在并发运行后的当前账本上重复提交，应原样返回首次结论
        RiskScoreResult f3 = concurrentScores.get("F3");
        SubmissionOutcome again = scoringService.submit(Transactions.builder("F3", "A1")
                .amount("6000").time("2026-09-26T01:06:00Z").cp("CP-9").country("KY").build());
        assertThat(again.duplicate()).isTrue();
        assertThat(again.accepted()).isTrue();
        assertThat(again.score()).isEqualTo(f3);
        assertThat(caseService.all()).hasSize(2);
    }

    private Map<String, RiskScoreResult> snapshotScores() {
        return ledger.allScores().stream()
                .collect(Collectors.toMap(RiskScoreResult::transactionId, Function.identity()));
    }

    private Map<String, RejectionReason> snapshotRejections() {
        return ledger.allRejections().stream()
                .collect(Collectors.toMap(RejectionRecord::transactionId, RejectionRecord::reason));
    }

    private void logReport(String label, BatchReport r) {
        log.info("[UT-ARRIVE] {}到达: 输入={} 唯一={} 接受={} 拒绝={} 重复={} 开案={}",
                label, r.inputs(), r.uniqueTransactions(), r.accepted(),
                r.rejected(), r.duplicates(), r.casesOpened());
    }

    private List<String> summarizeScores(Map<String, RiskScoreResult> scores) {
        return scores.entrySet().stream()
                .sorted(Comparator.comparing(Map.Entry::getKey))
                .map(e -> e.getKey() + "=" + e.getValue().totalScore() + "/" + e.getValue().level())
                .toList();
    }
}
