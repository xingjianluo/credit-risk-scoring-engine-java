package com.github.xingjianluo.creditriskscoringenginejava.tests;

import com.github.xingjianluo.creditriskscoringenginejava.caseflow.ReviewCaseService;
import com.github.xingjianluo.creditriskscoringenginejava.exception.BatchDispositionRejectedException;
import com.github.xingjianluo.creditriskscoringenginejava.ledger.TransactionLedger;
import com.github.xingjianluo.creditriskscoringenginejava.model.BatchDispositionRequest;
import com.github.xingjianluo.creditriskscoringenginejava.model.BatchDispositionResult;
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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 案件批量处置：all-or-nothing、批次幂等、失败原因可区分、并发等价串行、终态保护。
 */
@SpringBootTest
@Import(FixedClockTestConfig.class)
class BatchDispositionTest {

    private static final Logger log = LoggerFactory.getLogger(BatchDispositionTest.class);

    @Autowired
    private RiskScoringService scoringService;
    @Autowired
    private ReviewCaseService caseService;
    @Autowired
    private TransactionLedger ledger;

    private List<String> caseIds;

    @BeforeEach
    void reset() {
        ledger.clear();
        caseService.clear();
        // 同一账户三笔高风险流水 -> 三个 OPEN 案件
        List<Transaction> feed = List.of(
                highRisk("B1", "OPS1", "2026-09-26T01:05:00Z", "CP-A"),
                highRisk("B2", "OPS1", "2026-09-26T01:15:00Z", "CP-B"),
                highRisk("B3", "OPS1", "2026-09-26T01:25:00Z", "CP-C"));
        scoringService.submitBatch(feed);
        caseIds = caseService.all().stream().map(ReviewCase::getCaseId).sorted().toList();
        assertThat(caseIds).hasSize(3);
        log.info("[UT-BATCH] 准备案件={} 状态={}", caseIds,
                caseIds.stream().map(id -> caseService.get(id).getStatus()).toList());
    }

    private static Transaction highRisk(String id, String account, String isoTime, String cp) {
        return Transactions.builder(id, account).amount("6000")
                .time(isoTime).cp(cp).country("KY").build();
    }

    private static DispositionRequest item(String caseId, CaseAction action, ReviewerRole role) {
        return new DispositionRequest(caseId, action, "ops-" + caseId, role, "批量处置");
    }

    private static BatchDispositionRequest batch(String batchId, CaseAction action,
                                                 ReviewerRole role, List<String> caseIds) {
        return new BatchDispositionRequest(batchId,
                caseIds.stream().map(id -> item(id, action, role)).toList());
    }

    @Test
    @DisplayName("批量推进 OPEN->INVESTIGATING->RESOLVED，逐案留痕")
    void batchAdvancesAllCases() {
        BatchDispositionResult r1 = caseService.applyBatch(
                batch("BATCH-1", CaseAction.START_INVESTIGATION, ReviewerRole.ANALYST, caseIds));
        assertThat(r1.duplicate()).isFalse();
        assertThat(r1.appliedCaseIds()).containsExactlyInAnyOrderElementsOf(caseIds);
        for (String id : caseIds) {
            assertThat(caseService.get(id).getStatus()).isEqualTo(CaseStatus.INVESTIGATING);
        }

        BatchDispositionResult r2 = caseService.applyBatch(
                batch("BATCH-2", CaseAction.RESOLVE, ReviewerRole.SENIOR, caseIds));
        assertThat(r2.duplicate()).isFalse();
        for (String id : caseIds) {
            ReviewCase c = caseService.get(id);
            assertThat(c.getStatus()).isEqualTo(CaseStatus.RESOLVED);
            // 开案事件 + 两次处置事件
            assertThat(c.getEvents()).hasSize(3);
            log.info("[UT-BATCH] 案件={} 事件链={}", id, c.getEvents().stream()
                    .map(e -> e.from() + "->" + e.to()).toList());
        }
    }

    @Test
    @DisplayName("同一 batchId 重复提交幂等：返回首次结果，不产生重复处置记录")
    void batchIdempotentByBatchId() {
        BatchDispositionRequest req = batch("BATCH-IDEM", CaseAction.START_INVESTIGATION,
                ReviewerRole.ANALYST, caseIds);
        BatchDispositionResult first = caseService.applyBatch(req);
        BatchDispositionResult second = caseService.applyBatch(req);
        BatchDispositionResult third = caseService.applyBatch(req);

        assertThat(first.duplicate()).isFalse();
        assertThat(second.duplicate()).isTrue();
        assertThat(third.duplicate()).isTrue();
        assertThat(second.appliedCaseIds()).isEqualTo(first.appliedCaseIds());
        assertThat(second.appliedAt()).isEqualTo(first.appliedAt());
        // 每案只有 开案 + 一次处置 两条事件，没有重复处置记录
        for (String id : caseIds) {
            ReviewCase c = caseService.get(id);
            assertThat(c.getStatus()).isEqualTo(CaseStatus.INVESTIGATING);
            assertThat(c.getEvents()).hasSize(2);
        }
        log.info("[UT-BATCH] 幂等批次 BATCH-IDEM 重复 3 次，每案事件数=2（无重复处置）");
    }

    @Test
    @DisplayName("批次中一项权限不足 -> 整批拒绝（原因可区分），无任何状态变更")
    void batchAllOrNothingOnPermissionDenied() {
        List<DispositionRequest> items = new ArrayList<>();
        items.add(item(caseIds.get(0), CaseAction.RESOLVE, ReviewerRole.SENIOR));
        items.add(item(caseIds.get(1), CaseAction.RESOLVE, ReviewerRole.SENIOR));
        // ANALYST 无权 RESOLVE（需要 SENIOR）
        items.add(item(caseIds.get(2), CaseAction.RESOLVE, ReviewerRole.ANALYST));

        assertThatThrownBy(() -> caseService.applyBatch(new BatchDispositionRequest("BATCH-PERM", items)))
                .isInstanceOfSatisfying(BatchDispositionRejectedException.class, e -> {
                    assertThat(e.failures()).hasSize(1);
                    assertThat(e.failures().get(0).caseId()).isEqualTo(caseIds.get(2));
                    assertThat(e.failures().get(0).errorCode()).isEqualTo("CASE_PERMISSION_DENIED");
                    assertThat(e.allPermissionDenied()).isTrue();
                    log.info("[UT-BATCH] 权限不足整批拒绝: {}", e.failures());
                });
        // 整批无半成功：所有案件仍是 OPEN，只有开案事件
        for (String id : caseIds) {
            ReviewCase c = caseService.get(id);
            assertThat(c.getStatus()).isEqualTo(CaseStatus.OPEN);
            assertThat(c.getEvents()).hasSize(1);
        }
    }

    @Test
    @DisplayName("批次中一项非法迁移 -> 整批拒绝，终态保护保留，原因与权限不足可区分")
    void batchAllOrNothingOnIllegalTransition() {
        // 先把 caseIds[2] 单独推进到 RESOLVED（终态）
        caseService.apply(item(caseIds.get(2), CaseAction.RESOLVE, ReviewerRole.SENIOR));

        List<DispositionRequest> items = new ArrayList<>();
        items.add(item(caseIds.get(0), CaseAction.START_INVESTIGATION, ReviewerRole.ANALYST));
        items.add(item(caseIds.get(1), CaseAction.START_INVESTIGATION, ReviewerRole.ANALYST));
        // RESOLVED 不允许 START_INVESTIGATION（终态保护）
        items.add(item(caseIds.get(2), CaseAction.START_INVESTIGATION, ReviewerRole.ANALYST));

        assertThatThrownBy(() -> caseService.applyBatch(new BatchDispositionRequest("BATCH-ILL", items)))
                .isInstanceOfSatisfying(BatchDispositionRejectedException.class, e -> {
                    assertThat(e.failures()).hasSize(1);
                    assertThat(e.failures().get(0).errorCode()).isEqualTo("ILLEGAL_CASE_TRANSITION");
                    assertThat(e.allPermissionDenied()).isFalse();
                    log.info("[UT-BATCH] 非法迁移整批拒绝: {}", e.failures());
                });
        // 合法的两项也未生效（all-or-nothing）
        assertThat(caseService.get(caseIds.get(0)).getStatus()).isEqualTo(CaseStatus.OPEN);
        assertThat(caseService.get(caseIds.get(1)).getStatus()).isEqualTo(CaseStatus.OPEN);
        assertThat(caseService.get(caseIds.get(0)).getEvents()).hasSize(1);
        // 终态案件保持 RESOLVED 且只有既有事件
        assertThat(caseService.get(caseIds.get(2)).getStatus()).isEqualTo(CaseStatus.RESOLVED);
        assertThat(caseService.get(caseIds.get(2)).getEvents()).hasSize(2);
    }

    @Test
    @DisplayName("批次中案件不存在 -> 整批拒绝，原因码 CASE_NOT_FOUND 可区分")
    void batchAllOrNothingOnCaseNotFound() {
        List<DispositionRequest> items = new ArrayList<>();
        items.add(item(caseIds.get(0), CaseAction.START_INVESTIGATION, ReviewerRole.ANALYST));
        items.add(item("CASE-999999", CaseAction.START_INVESTIGATION, ReviewerRole.ANALYST));

        assertThatThrownBy(() -> caseService.applyBatch(new BatchDispositionRequest("BATCH-NF", items)))
                .isInstanceOfSatisfying(BatchDispositionRejectedException.class, e -> {
                    assertThat(e.failures()).hasSize(1);
                    assertThat(e.failures().get(0).errorCode()).isEqualTo("CASE_NOT_FOUND");
                    log.info("[UT-BATCH] 案件不存在整批拒绝: {}", e.failures());
                });
        assertThat(caseService.get(caseIds.get(0)).getStatus()).isEqualTo(CaseStatus.OPEN);
        assertThat(caseService.get(caseIds.get(0)).getEvents()).hasSize(1);
    }

    @Test
    @DisplayName("并发提交同一 batchId：只生效一次，结果与串行一致")
    void concurrentSameBatchIdAppliesOnce() {
        BatchDispositionRequest req = batch("BATCH-CONC", CaseAction.START_INVESTIGATION,
                ReviewerRole.ANALYST, caseIds);
        int threads = 8;
        CyclicBarrier barrier = new CyclicBarrier(threads);
        AtomicInteger nonDuplicate = new AtomicInteger();
        List<CompletableFuture<BatchDispositionResult>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(CompletableFuture.supplyAsync(() -> {
                try {
                    barrier.await();
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
                BatchDispositionResult r = caseService.applyBatch(req);
                if (!r.duplicate()) {
                    nonDuplicate.incrementAndGet();
                }
                return r;
            }));
        }
        List<BatchDispositionResult> results = futures.stream().map(CompletableFuture::join).toList();

        assertThat(nonDuplicate.get()).isEqualTo(1);
        assertThat(results).allSatisfy(r ->
                assertThat(r.appliedCaseIds()).containsExactlyInAnyOrderElementsOf(caseIds));
        for (String id : caseIds) {
            ReviewCase c = caseService.get(id);
            assertThat(c.getStatus()).isEqualTo(CaseStatus.INVESTIGATING);
            assertThat(c.getEvents()).hasSize(2);
        }
        log.info("[UT-BATCH] 并发 {} 线程同 batchId，仅 1 次生效，每案事件数=2", threads);
    }

    @Test
    @DisplayName("并发不同批次竞争同一批案件：最终状态与审计和串行执行一致")
    void concurrentDistinctBatchesMatchSerialOutcome() {
        // 两个批次都尝试直接 RESOLVE 全部案件：串行意义下只有一个能成功
        BatchDispositionRequest b1 = batch("BATCH-R1", CaseAction.RESOLVE, ReviewerRole.SENIOR, caseIds);
        BatchDispositionRequest b2 = batch("BATCH-R2", CaseAction.RESOLVE, ReviewerRole.SENIOR, caseIds);
        CyclicBarrier barrier = new CyclicBarrier(2);
        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        java.util.concurrent.atomic.AtomicReference<BatchDispositionRequest> pick =
                new java.util.concurrent.atomic.AtomicReference<>();
        Runnable race = () -> {
            // 两个线程分别取走 b1 / b2 之一
            BatchDispositionRequest mine = pick.compareAndSet(null, b1) ? b1 : b2;
            try {
                barrier.await();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            try {
                caseService.applyBatch(mine);
                succeeded.incrementAndGet();
            } catch (BatchDispositionRejectedException e) {
                rejected.incrementAndGet();
                log.info("[UT-BATCH] 竞争失败批次: {}", e.failures());
            }
        };
        CompletableFuture<Void> f1 = CompletableFuture.runAsync(race);
        CompletableFuture<Void> f2 = CompletableFuture.runAsync(race);
        CompletableFuture.allOf(f1, f2).join();

        // 串行等价：恰好一个批次成功，另一个因终态保护整批拒绝
        assertThat(succeeded.get()).isEqualTo(1);
        assertThat(rejected.get()).isEqualTo(1);
        for (String id : caseIds) {
            ReviewCase c = caseService.get(id);
            assertThat(c.getStatus()).isEqualTo(CaseStatus.RESOLVED);
            // 开案 + 恰好一次 RESOLVE，无重复/半成功处置
            assertThat(c.getEvents()).hasSize(2);
        }
        log.info("[UT-BATCH] 并发竞争批次：成功={} 拒绝={}，最终全部 RESOLVED 且每案事件数=2",
                succeeded.get(), rejected.get());
    }
}
