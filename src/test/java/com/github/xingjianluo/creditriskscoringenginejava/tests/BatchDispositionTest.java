package com.github.xingjianluo.creditriskscoringenginejava.tests;

import com.github.xingjianluo.creditriskscoringenginejava.caseflow.AccountCaseService;
import com.github.xingjianluo.creditriskscoringenginejava.caseflow.BatchDispositionService;
import com.github.xingjianluo.creditriskscoringenginejava.caseflow.ReviewCaseService;
import com.github.xingjianluo.creditriskscoringenginejava.exception.BatchDispositionRejectedException;
import com.github.xingjianluo.creditriskscoringenginejava.ledger.TransactionLedger;
import com.github.xingjianluo.creditriskscoringenginejava.model.BatchDispositionReport;
import com.github.xingjianluo.creditriskscoringenginejava.model.BatchDispositionRequest;
import com.github.xingjianluo.creditriskscoringenginejava.model.CaseAction;
import com.github.xingjianluo.creditriskscoringenginejava.model.CaseStatus;
import com.github.xingjianluo.creditriskscoringenginejava.model.DispositionRequest;
import com.github.xingjianluo.creditriskscoringenginejava.model.ReviewCase;
import com.github.xingjianluo.creditriskscoringenginejava.model.ReviewerRole;
import com.github.xingjianluo.creditriskscoringenginejava.model.Transaction;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 案件批量处置：整批原子、幂等回放、可区分失败原因、并发等价串行、终态保护。
 */
@SpringBootTest
@Import(FixedClockTestConfig.class)
class BatchDispositionTest {

    private static final Logger log = LoggerFactory.getLogger(BatchDispositionTest.class);
    private static final String ACCOUNT = "BATCH-ACC";

    @Autowired
    private RiskScoringService scoringService;
    @Autowired
    private ReviewCaseService caseService;
    @Autowired
    private BatchDispositionService batchService;
    @Autowired
    private AccountCaseService accountCaseService;
    @Autowired
    private TransactionLedger ledger;

    @BeforeEach
    void reset() {
        ledger.clear();
        caseService.clear();
        accountCaseService.clear();
        batchService.clear();
    }

    private ReviewCase openCase(String txId) {
        Transaction tx = Transactions.builder(txId, ACCOUNT).amount("20000")
                .time("2026-09-26T02:00:00Z").cp("CP-" + txId).country("KY").build();
        scoringService.submit(tx);
        ReviewCase c = caseService.findByTransactionId(txId).orElseThrow();
        log.info("[UT-BATCH] 开案 caseId={} tx={} 状态={}", c.getCaseId(), txId, c.getStatus());
        return c;
    }

    private BatchDispositionRequest batch(String batchId, List<String> caseIds,
                                          CaseAction action, ReviewerRole role) {
        return new BatchDispositionRequest(batchId, ACCOUNT, caseIds, action,
                "operator-1", role, "批量处置-" + action);
    }

    @Test
    @DisplayName("批量推进：整批应用，审计事件带批次号")
    void batchApplyHappyPath() {
        ReviewCase c1 = openCase("BD-1");
        ReviewCase c2 = openCase("BD-2");

        BatchDispositionReport report = batchService.applyBatch(batch("B-1",
                List.of(c1.getCaseId(), c2.getCaseId()),
                CaseAction.START_INVESTIGATION, ReviewerRole.ANALYST));

        assertThat(report.applied()).isEqualTo(2);
        assertThat(report.duplicate()).isFalse();
        for (ReviewCase c : List.of(caseService.get(c1.getCaseId()), caseService.get(c2.getCaseId()))) {
            assertThat(c.getStatus()).isEqualTo(CaseStatus.INVESTIGATING);
            assertThat(c.getEvents().get(c.getEvents().size() - 1).batchId()).isEqualTo("B-1");
        }
        log.info("[UT-BATCH] 整批应用 batchId=B-1 applied={} 状态=INVESTIGATING", report.applied());
    }

    @Test
    @DisplayName("同一 batchId 重复提交幂等：返回首次报告，不重复处置、不重复留痕")
    void idempotentReplay() {
        ReviewCase c1 = openCase("BD-I1");
        ReviewCase c2 = openCase("BD-I2");
        List<String> ids = List.of(c1.getCaseId(), c2.getCaseId());

        BatchDispositionReport first = batchService.applyBatch(
                batch("B-IDEM", ids, CaseAction.START_INVESTIGATION, ReviewerRole.ANALYST));
        int eventsC1 = caseService.get(c1.getCaseId()).getEvents().size();

        BatchDispositionReport replay = batchService.applyBatch(
                batch("B-IDEM", ids, CaseAction.START_INVESTIGATION, ReviewerRole.ANALYST));
        BatchDispositionReport replay2 = batchService.applyBatch(
                batch("B-IDEM", ids, CaseAction.RESOLVE, ReviewerRole.SENIOR));

        assertThat(replay.duplicate()).isTrue();
        assertThat(replay.applied()).isEqualTo(first.applied());
        assertThat(replay2.duplicate()).isTrue();
        assertThat(caseService.get(c1.getCaseId()).getEvents()).hasSize(eventsC1);
        assertThat(caseService.get(c1.getCaseId()).getStatus()).isEqualTo(CaseStatus.INVESTIGATING);
        log.info("[UT-BATCH] 幂等回放 batchId=B-IDEM applied={} 事件数不变={}",
                replay.applied(), eventsC1);
    }

    @Test
    @DisplayName("任一案件状态不允许迁移则整批拒绝：原因可区分且无半成功")
    void atomicRejectOnIllegalTransition() {
        ReviewCase c1 = openCase("BD-R1");
        ReviewCase c2 = openCase("BD-R2");
        // c2 先走到终态 RESOLVED
        caseService.apply(new DispositionRequest(c2.getCaseId(), CaseAction.RESOLVE,
                "senior-1", ReviewerRole.SENIOR, "先结案"));
        int eventsC1Before = caseService.get(c1.getCaseId()).getEvents().size();

        assertThatThrownBy(() -> batchService.applyBatch(batch("B-REJ",
                List.of(c1.getCaseId(), c2.getCaseId()),
                CaseAction.START_INVESTIGATION, ReviewerRole.ANALYST)))
                .isInstanceOf(BatchDispositionRejectedException.class)
                .satisfies(e -> {
                    BatchDispositionRejectedException ex = (BatchDispositionRejectedException) e;
                    assertThat(ex.failures()).hasSize(1);
                    assertThat(ex.failures().get(0).caseId()).isEqualTo(c2.getCaseId());
                    assertThat(ex.failures().get(0).errorCode()).isEqualTo("ILLEGAL_CASE_TRANSITION");
                });
        // 整批原子：c1 状态与审计均未变化
        assertThat(caseService.get(c1.getCaseId()).getStatus()).isEqualTo(CaseStatus.OPEN);
        assertThat(caseService.get(c1.getCaseId()).getEvents()).hasSize(eventsC1Before);
        assertThat(caseService.get(c2.getCaseId()).getStatus()).isEqualTo(CaseStatus.RESOLVED);
        log.info("[UT-BATCH] 整批拒绝: c2=RESOLVED 不允许 START_INVESTIGATION，c1 保持 OPEN 无半成功");
    }

    @Test
    @DisplayName("权限不足与账户不匹配给出可区分原因，整批不变更")
    void distinguishableFailures() {
        ReviewCase c1 = openCase("BD-P1");
        ReviewCase c2 = openCase("BD-P2");

        // 权限不足：ANALYST 执行 RESOLVE（需 SENIOR）
        assertThatThrownBy(() -> batchService.applyBatch(batch("B-PERM",
                List.of(c1.getCaseId(), c2.getCaseId()), CaseAction.RESOLVE, ReviewerRole.ANALYST)))
                .isInstanceOf(BatchDispositionRejectedException.class)
                .satisfies(e -> {
                    BatchDispositionRejectedException ex = (BatchDispositionRejectedException) e;
                    assertThat(ex.allPermissionDenied()).isTrue();
                    assertThat(ex.failures()).allMatch(f -> "CASE_PERMISSION_DENIED".equals(f.errorCode()));
                });
        assertThat(caseService.get(c1.getCaseId()).getStatus()).isEqualTo(CaseStatus.OPEN);

        // 账户不匹配：批次账户与案件归属账户不一致
        assertThatThrownBy(() -> batchService.applyBatch(new BatchDispositionRequest(
                "B-ACCT", "OTHER-ACC", List.of(c1.getCaseId()), CaseAction.ASSIGN,
                "op", ReviewerRole.ANALYST, null)))
                .isInstanceOf(BatchDispositionRejectedException.class)
                .satisfies(e -> assertThat(((BatchDispositionRejectedException) e).failures()
                        .get(0).errorCode()).isEqualTo("ACCOUNT_MISMATCH"));

        // 案件不存在
        assertThatThrownBy(() -> batchService.applyBatch(batch("B-NF",
                List.of("CASE-999999"), CaseAction.ASSIGN, ReviewerRole.ANALYST)))
                .isInstanceOf(BatchDispositionRejectedException.class)
                .satisfies(e -> assertThat(((BatchDispositionRejectedException) e).failures()
                        .get(0).errorCode()).isEqualTo("CASE_NOT_FOUND"));
        log.info("[UT-BATCH] 可区分原因: CASE_PERMISSION_DENIED / ACCOUNT_MISMATCH / CASE_NOT_FOUND");
    }

    @Test
    @DisplayName("终态保护保留：RESOLVED 只能 REOPEN，且需 SENIOR")
    void terminalStateProtection() {
        ReviewCase c1 = openCase("BD-T1");
        caseService.apply(new DispositionRequest(c1.getCaseId(), CaseAction.RESOLVE,
                "senior-1", ReviewerRole.SENIOR, "结案"));

        // ANALYST 重开：权限不足
        assertThatThrownBy(() -> batchService.applyBatch(batch("B-T1",
                List.of(c1.getCaseId()), CaseAction.REOPEN, ReviewerRole.ANALYST)))
                .isInstanceOf(BatchDispositionRejectedException.class);
        assertThat(caseService.get(c1.getCaseId()).getStatus()).isEqualTo(CaseStatus.RESOLVED);

        // SENIOR 重开：合法
        BatchDispositionReport report = batchService.applyBatch(batch("B-T2",
                List.of(c1.getCaseId()), CaseAction.REOPEN, ReviewerRole.SENIOR));
        assertThat(report.applied()).isEqualTo(1);
        assertThat(caseService.get(c1.getCaseId()).getStatus()).isEqualTo(CaseStatus.REOPENED);
        log.info("[UT-BATCH] 终态保护: RESOLVED 仅 SENIOR 可 REOPEN -> REOPENED");
    }

    @Test
    @DisplayName("并发批量处置：同 batchId 只应用一次；不同批次结果等价串行")
    void concurrentBatches() {
        List<ReviewCase> cases = IntStream.rangeClosed(1, 4)
                .mapToObj(i -> openCase("BD-C" + i)).toList();
        List<String> ids = cases.stream().map(ReviewCase::getCaseId).toList();

        // 1) 同一 batchId 并发 8 次：恰好应用一次
        int threads = 8;
        CyclicBarrier barrier = new CyclicBarrier(threads);
        List<CompletableFuture<BatchDispositionReport>> futures = IntStream.range(0, threads)
                .mapToObj(i -> CompletableFuture.supplyAsync(() -> {
                    try {
                        barrier.await();
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                    return batchService.applyBatch(batch("B-CONC", ids,
                            CaseAction.START_INVESTIGATION, ReviewerRole.ANALYST));
                })).toList();
        List<BatchDispositionReport> reports = futures.stream().map(CompletableFuture::join).toList();
        assertThat(reports.stream().filter(r -> !r.duplicate()).count())
                .as("同 batchId 并发恰好应用一次").isEqualTo(1);
        assertThat(reports).allSatisfy(r -> assertThat(r.applied()).isEqualTo(4));
        for (String id : ids) {
            ReviewCase c = caseService.get(id);
            assertThat(c.getStatus()).isEqualTo(CaseStatus.INVESTIGATING);
            assertThat(c.getEvents()).as("不重复留痕").hasSize(2); // 开案事件 + 1 次批量处置
        }

        // 2) 两个不同批次并发 RESOLVE：串行等价——先执行者成功，
        //    后执行者面对终态 RESOLVED 整批拒绝（终态保护），最终状态一致
        CompletableFuture<String> f1 = CompletableFuture.supplyAsync(() -> {
            try {
                batchService.applyBatch(batch("B-X", ids, CaseAction.RESOLVE, ReviewerRole.SENIOR));
                return "OK";
            } catch (BatchDispositionRejectedException e) {
                return "REJECTED";
            }
        });
        CompletableFuture<String> f2 = CompletableFuture.supplyAsync(() -> {
            try {
                batchService.applyBatch(batch("B-Y", ids, CaseAction.RESOLVE, ReviewerRole.SENIOR));
                return "OK";
            } catch (BatchDispositionRejectedException e) {
                return "REJECTED";
            }
        });
        List<String> outcomes = List.of(f1.join(), f2.join());
        assertThat(outcomes).containsExactlyInAnyOrder("OK", "REJECTED");
        for (String id : ids) {
            ReviewCase c = caseService.get(id);
            assertThat(c.getStatus()).isEqualTo(CaseStatus.RESOLVED);
            // 开案 + 批量调查 + 恰好一次批量结案
            assertThat(c.getEvents()).hasSize(3);
        }
        log.info("[UT-BATCH] 并发批次: 一成功一整批拒绝，全部案件终态=RESOLVED，事件数=3");
    }
}
