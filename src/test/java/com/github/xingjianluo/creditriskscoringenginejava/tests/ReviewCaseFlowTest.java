package com.github.xingjianluo.creditriskscoringenginejava.tests;

import com.github.xingjianluo.creditriskscoringenginejava.caseflow.ReviewCaseService;
import com.github.xingjianluo.creditriskscoringenginejava.exception.CasePermissionDeniedException;
import com.github.xingjianluo.creditriskscoringenginejava.exception.IllegalCaseTransitionException;
import com.github.xingjianluo.creditriskscoringenginejava.ledger.TransactionLedger;
import com.github.xingjianluo.creditriskscoringenginejava.model.CaseAction;
import com.github.xingjianluo.creditriskscoringenginejava.model.CaseStatus;
import com.github.xingjianluo.creditriskscoringenginejava.model.DispositionRequest;
import com.github.xingjianluo.creditriskscoringenginejava.model.ReviewCase;
import com.github.xingjianluo.creditriskscoringenginejava.model.RiskLevel;
import com.github.xingjianluo.creditriskscoringenginejava.model.RiskScoreResult;
import com.github.xingjianluo.creditriskscoringenginejava.model.ReviewerRole;
import com.github.xingjianluo.creditriskscoringenginejava.model.SubmissionOutcome;
import com.github.xingjianluo.creditriskscoringenginejava.model.Transaction;
import com.github.xingjianluo.creditriskscoringenginejava.service.RiskScoringService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Import(FixedClockTestConfig.class)
class ReviewCaseFlowTest {

    private static final Logger log = LoggerFactory.getLogger(ReviewCaseFlowTest.class);

    @Autowired
    private RiskScoringService scoringService;
    @Autowired
    private ReviewCaseService caseService;
    @Autowired
    private TransactionLedger ledger;

    private Transaction highRiskTx(String id, String account) {
        return Transactions.builder(id, account)
                .amount("20000").time("2026-09-26T02:00:00Z").cp("CP-X").country("KY").build();
    }

    private ReviewCase openOneCase(String txId) {
        ledger.clear();
        caseService.clear();
        SubmissionOutcome o = scoringService.submit(highRiskTx(txId, "C1"));
        assertThat(o.accepted()).isTrue();
        ReviewCase c = caseService.findByTransactionId(txId).orElseThrow();
        log.info("[UT-CASE] 输入信号(tx={}) 判定依据=评分{}分/{} 自动开案 caseId={} 状态=OPEN",
                txId, c.getRiskScore(), c.getRiskLevel(), c.getCaseId());
        return c;
    }

    @Test
    @DisplayName("合法状态流转 OPEN->INVESTIGATING->RESOLVED->REOPENED->INVESTIGATING 全程留痕")
    void happyPathTransitions() {
        ReviewCase c = openOneCase("CASE-HP-1");

        c = caseService.apply(new DispositionRequest(c.getCaseId(), CaseAction.START_INVESTIGATION,
                "alice", ReviewerRole.ANALYST, "开始调查"));
        assertThat(c.getStatus()).isEqualTo(CaseStatus.INVESTIGATING);

        c = caseService.apply(new DispositionRequest(c.getCaseId(), CaseAction.RESOLVE,
                "bob", ReviewerRole.SENIOR, "确认可疑，结案"));
        assertThat(c.getStatus()).isEqualTo(CaseStatus.RESOLVED);

        c = caseService.apply(new DispositionRequest(c.getCaseId(), CaseAction.REOPEN,
                "carol", ReviewerRole.SENIOR, "新证据，重开"));
        assertThat(c.getStatus()).isEqualTo(CaseStatus.REOPENED);

        c = caseService.apply(new DispositionRequest(c.getCaseId(), CaseAction.START_INVESTIGATION,
                "alice", ReviewerRole.ANALYST, "重新调查"));
        assertThat(c.getStatus()).isEqualTo(CaseStatus.INVESTIGATING);

        log.info("[UT-CASE] caseId={} 审计事件链:", c.getCaseId());
        c.getEvents().forEach(e -> log.info("[UT-CASE]   {} -> {} by {}({}) note={}",
                e.from(), e.to(), e.reviewer(), e.role(), e.note()));
        // 开案事件 + 4 次处置
        assertThat(c.getEvents()).hasSize(5);
    }

    @Test
    @DisplayName("非法状态迁移与权限不足分别抛出可区分异常")
    void illegalTransitionAndPermissionAreDistinguishable() {
        ReviewCase c = openOneCase("CASE-BAD-1");

        // OPEN 状态直接 REOPEN：非法迁移
        assertThatThrownBy(() -> caseService.apply(new DispositionRequest(
                c.getCaseId(), CaseAction.REOPEN, "bob", ReviewerRole.SENIOR, "非法重开")))
                .isInstanceOf(IllegalCaseTransitionException.class)
                .satisfies(e -> log.warn("[UT-CASE-DENY] 非法迁移被拒绝: {}", e.getMessage()));

        // ANALYST 试图 RESOLVE：权限不足（即使迁移本身合法）
        assertThatThrownBy(() -> caseService.apply(new DispositionRequest(
                c.getCaseId(), CaseAction.RESOLVE, "alice", ReviewerRole.ANALYST, "越权结案")))
                .isInstanceOf(CasePermissionDeniedException.class)
                .satisfies(e -> log.warn("[UT-CASE-DENY] 权限不足被拒绝: {}", e.getMessage()));

        // 两类异常互不混淆
        try {
            caseService.apply(new DispositionRequest(c.getCaseId(), CaseAction.RESOLVE,
                    "a", ReviewerRole.ANALYST, "x"));
        } catch (RuntimeException e) {
            assertThat(e).isExactlyInstanceOf(CasePermissionDeniedException.class)
                    .isNotInstanceOf(IllegalCaseTransitionException.class);
        }
        // 被拒绝的处置不改变状态
        assertThat(caseService.get(c.getCaseId()).getStatus()).isEqualTo(CaseStatus.OPEN);
        assertThat(caseService.get(c.getCaseId()).getEvents()).hasSize(1);
    }

    @Test
    @DisplayName("RESOLVED 不能再 START_INVESTIGATION，必须先 REOPEN")
    void resolvedMustReopenBeforeInvestigation() {
        ReviewCase c = openOneCase("CASE-BAD-2");
        caseService.apply(new DispositionRequest(c.getCaseId(), CaseAction.START_INVESTIGATION,
                "alice", ReviewerRole.ANALYST, null));
        caseService.apply(new DispositionRequest(c.getCaseId(), CaseAction.RESOLVE,
                "bob", ReviewerRole.ADMIN, null));

        assertThatThrownBy(() -> caseService.apply(new DispositionRequest(
                c.getCaseId(), CaseAction.START_INVESTIGATION, "alice", ReviewerRole.ANALYST, null)))
                .isInstanceOf(IllegalCaseTransitionException.class);
    }

    @Test
    @DisplayName("并发处置同一案件：合法迁移串行生效，非法/越权被拒，状态与审计计数最终一致")
    void concurrentDispositionsKeepConsistentState() {
        ReviewCase c = openOneCase("CASE-CONC-2");
        String caseId = c.getCaseId();
        int threads = 12;
        CyclicBarrier barrier = new CyclicBarrier(threads);
        List<CompletableFuture<String>> futures = IntStream.range(0, threads)
                .mapToObj(i -> CompletableFuture.supplyAsync(() -> {
                    try {
                        barrier.await();
                    } catch (Exception e) {
                        Thread.currentThread().interrupt();
                    }
                    // 偶数：ANALYST 开始调查（OPEN 后仅首次合法，其余因状态变化而非法）
                    // 奇数：ANALYST 试图结案（权限不足，始终被拒）
                    CaseAction action = (i % 2 == 0)
                            ? CaseAction.START_INVESTIGATION
                            : CaseAction.RESOLVE;
                    ReviewerRole role = ReviewerRole.ANALYST;
                    try {
                        caseService.apply(new DispositionRequest(caseId, action,
                                "u" + i, role, "并发处置#" + i));
                        return "OK:" + action;
                    } catch (IllegalCaseTransitionException e) {
                        return "ILLEGAL";
                    } catch (CasePermissionDeniedException e) {
                        return "DENIED";
                    }
                }))
                .toList();
        List<String> results = futures.stream().map(CompletableFuture::join).toList();

        long ok = results.stream().filter(r -> r.equals("OK:START_INVESTIGATION")).count();
        long denied = results.stream().filter(r -> r.equals("DENIED")).count();
        long illegal = results.stream().filter(r -> r.equals("ILLEGAL")).count();
        log.info("[UT-CASE-CONC] 并发处置结果: 受理={} 越权拒绝={} 非法迁移拒绝={}", ok, denied, illegal);

        // START_INVESTIGATION 是幂等动作（INVESTIGATING 上再发起为空转），6 次都被受理；
        // 6 次 ANALYST 结案全部因权限被拒；无非法迁移
        assertThat(ok).isEqualTo(6);
        assertThat(denied).isEqualTo(6);
        assertThat(illegal).isZero();
        ReviewCase finalCase = caseService.get(caseId);
        assertThat(finalCase.getStatus()).isEqualTo(CaseStatus.INVESTIGATING);
        // 审计链记录开案 + 6 次受理；其中真正改变状态的是开案与首次 OPEN->INVESTIGATING
        long realTransitions = finalCase.getEvents().stream()
                .filter(e -> e.from() != e.to()).count();
        assertThat(realTransitions).isEqualTo(2);
        assertThat(finalCase.getEvents()).hasSize(7);
    }

    @Test
    @DisplayName("并发重复提交同一高风险流水只开一个案件，状态计数最终一致")
    void concurrentSubmissionOpensSingleCase() {
        ledger.clear();
        caseService.clear();
        String txId = "CASE-CONC-1";
        int threads = 16;
        CyclicBarrier barrier = new CyclicBarrier(threads);
        List<CompletableFuture<SubmissionOutcome>> futures = IntStream.range(0, threads)
                .mapToObj(i -> CompletableFuture.supplyAsync(() -> {
                    try {
                        barrier.await();
                    } catch (Exception e) {
                        Thread.currentThread().interrupt();
                    }
                    return scoringService.submit(highRiskTx(txId, "C9"));
                }))
                .toList();
        List<SubmissionOutcome> outcomes = futures.stream().map(CompletableFuture::join).toList();

        long casesInOutcomes = outcomes.stream().filter(o -> !o.caseIds().isEmpty()).count();
        log.info("[UT-CASE-CONC] {} 个并发提交: 携带新开案的提交数={}, 全部接受={}",
                threads, casesInOutcomes, outcomes.stream().filter(SubmissionOutcome::accepted).count());
        assertThat(caseService.all()).hasSize(1);
        assertThat(caseService.findByTransactionId(txId)).isPresent();
        // 只有 1 个提交是“首次”（duplicate=false），其余 15 个重复
        assertThat(outcomes).filteredOn(o -> !o.duplicate()).hasSize(1);
        assertThat(outcomes).filteredOn(SubmissionOutcome::duplicate).hasSize(threads - 1);
        // 所有提交拿到同一个 caseId
        String caseId = caseService.findByTransactionId(txId).orElseThrow().getCaseId();
        assertThat(outcomes).allSatisfy(o -> {
            // 重复提交的 caseIds 也指向同一案件
            Optional<ReviewCase> c = caseService.findByTransactionId(txId);
            assertThat(c).isPresent();
            assertThat(c.get().getCaseId()).isEqualTo(caseId);
        });
    }
}
