package com.github.xingjianluo.creditriskscoringenginejava.caseflow;

import com.github.xingjianluo.creditriskscoringenginejava.exception.BatchDispositionRejectedException;
import com.github.xingjianluo.creditriskscoringenginejava.exception.CaseNotFoundException;
import com.github.xingjianluo.creditriskscoringenginejava.exception.CasePermissionDeniedException;
import com.github.xingjianluo.creditriskscoringenginejava.exception.IllegalCaseTransitionException;
import com.github.xingjianluo.creditriskscoringenginejava.model.BatchDispositionRequest;
import com.github.xingjianluo.creditriskscoringenginejava.model.BatchDispositionResult;
import com.github.xingjianluo.creditriskscoringenginejava.model.BatchItemFailure;
import com.github.xingjianluo.creditriskscoringenginejava.model.CaseAction;
import com.github.xingjianluo.creditriskscoringenginejava.model.CaseEvent;
import com.github.xingjianluo.creditriskscoringenginejava.model.CaseStatus;
import com.github.xingjianluo.creditriskscoringenginejava.model.DispositionRequest;
import com.github.xingjianluo.creditriskscoringenginejava.model.ReviewCase;
import com.github.xingjianluo.creditriskscoringenginejava.model.RiskLevel;
import com.github.xingjianluo.creditriskscoringenginejava.model.RiskScoreResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 可疑活动审查案件服务。
 *
 * <p>开案按 transactionId 幂等（{@code casesByTransaction} + computeIfAbsent），
 * 重复或乱序到达的同一高风险流水不会重复开案；
 * 处置对单个案件对象加锁，保证并发提交下状态流转与计数最终一致。
 */
@Service
public class ReviewCaseService {

    private static final Logger log = LoggerFactory.getLogger(ReviewCaseService.class);

    /** 达到该风险等级（含）才开案。 */
    public static final RiskLevel CASE_THRESHOLD = RiskLevel.HIGH;

    private final CaseStateMachine stateMachine;
    private final Clock clock;

    private final ConcurrentMap<String, ReviewCase> casesById = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String> txToCase = new ConcurrentHashMap<>();
    /** 批次幂等表：batchId -> 首次批量处置结果。 */
    private final ConcurrentMap<String, BatchDispositionResult> batchResults = new ConcurrentHashMap<>();
    /** 批次互斥锁：批量处置整体串行，保证并发批次与串行执行结果一致。 */
    private final Object batchMutex = new Object();
    private final AtomicLong sequence = new AtomicLong();

    public ReviewCaseService(CaseStateMachine stateMachine, Clock clock) {
        this.stateMachine = stateMachine;
        this.clock = clock;
    }

    /**
     * 依据评分结果在达到开案阈值时开案；已存在则返回既有案件（不重复开案）。
     * 未达阈值返回 empty。
     */
    public Optional<ReviewCase> openCaseIfNeeded(RiskScoreResult score) {
        if (score == null || score.level().ordinal() < CASE_THRESHOLD.ordinal()) {
            return Optional.empty();
        }
        ReviewCase created = new ReviewCase(nextCaseId(), score.transactionId(), score.accountId(),
                score.totalScore(), score.level(), score.ruleVersion(), Instant.now(clock));
        String existingCaseId = txToCase.computeIfAbsent(score.transactionId(), k -> created.getCaseId());
        if (!existingCaseId.equals(created.getCaseId())) {
            log.info("[CASE] 交易 {} 已有案件 {}，跳重复开案", score.transactionId(), existingCaseId);
            return Optional.of(casesById.get(existingCaseId));
        }
        casesById.put(created.getCaseId(), created);
        created.appendEvent(new CaseEvent(created.getCaseId(), null, CaseStatus.OPEN,
                "system", null, "风险等级 " + score.level() + "（" + score.totalScore()
                        + "分，规则版本 " + score.ruleVersion() + "）自动开案", Instant.now(clock)));
        log.info("[CASE] 自动开案 caseId={} tx={} score={} level={} version={}",
                created.getCaseId(), score.transactionId(), score.totalScore(),
                score.level(), score.ruleVersion());
        return Optional.of(created);
    }

    /** 应用处置动作；非法迁移/权限不足由状态机抛出可区分异常。 */
    public ReviewCase apply(DispositionRequest request) {
        ReviewCase target = get(request.caseId());
        target.getLock().lock();
        try {
            CaseStatus to = stateMachine.transition(target.getStatus(), request);
            mutate(target, request, to);
            return target;
        } finally {
            target.getLock().unlock();
        }
    }

    /**
     * 批量处置（all-or-nothing + 批次幂等）。
     *
     * <ul>
     *   <li>同一 batchId 重复提交返回首次结果（duplicate=true），不产生重复处置记录；</li>
     *   <li>先在所有目标案件锁内逐项校验（权限 + 迁移合法性），任一失败则整批拒绝，
     *       抛 {@link BatchDispositionRejectedException}（逐项可区分原因），无任何状态变更；</li>
     *   <li>案件按 caseId 固定顺序加锁，批次之间用批次互斥锁串行，
     *       并发批量处置的最终状态与审计记录和串行执行一致。</li>
     * </ul>
     */
    public BatchDispositionResult applyBatch(BatchDispositionRequest request) {
        if (request == null || request.items() == null || request.items().isEmpty()) {
            throw new IllegalArgumentException("批量处置请求为空：items 至少包含一项");
        }
        String batchId = request.batchId();
        if (batchId == null || batchId.isBlank()) {
            throw new IllegalArgumentException("批量处置缺少 batchId（批次幂等键）");
        }
        BatchDispositionResult existing = batchResults.get(batchId);
        if (existing != null) {
            log.info("[CASE-BATCH] batchId={} 重复提交，返回首次结果（已处置 {} 个案件）",
                    batchId, existing.appliedCaseIds().size());
            return existing.asDuplicate();
        }
        synchronized (batchMutex) {
            existing = batchResults.get(batchId);
            if (existing != null) {
                return existing.asDuplicate();
            }
            // 1) 解析目标案件（不存在的案件直接记为失败项）
            Map<String, ReviewCase> targets = new TreeMap<>();
            List<BatchItemFailure> failures = new ArrayList<>();
            for (DispositionRequest item : request.items()) {
                String caseId = item == null ? null : item.caseId();
                ReviewCase c = caseId == null ? null : casesById.get(caseId);
                if (c == null) {
                    failures.add(new BatchItemFailure(String.valueOf(caseId),
                            "CASE_NOT_FOUND", "审查案件不存在: " + caseId));
                } else {
                    targets.putIfAbsent(caseId, c);
                }
            }
            if (!failures.isEmpty()) {
                log.warn("[CASE-BATCH] batchId={} 整批拒绝：{}", batchId, failures);
                throw new BatchDispositionRejectedException(batchId, failures);
            }
            // 2) 按 caseId 固定顺序逐把加锁（全局顺序一致，避免死锁）
            List<ReviewCase> locked = new ArrayList<>(targets.values());
            lockAll(locked);
            try {
                // 3) 锁内逐项校验：任一不合法则整批拒绝，此时尚未产生任何变更
                for (DispositionRequest item : request.items()) {
                    ReviewCase target = targets.get(item.caseId());
                    try {
                        stateMachine.transition(target.getStatus(), item);
                    } catch (CasePermissionDeniedException | IllegalCaseTransitionException e) {
                        String code = e instanceof CasePermissionDeniedException
                                ? "CASE_PERMISSION_DENIED" : "ILLEGAL_CASE_TRANSITION";
                        List<BatchItemFailure> failure = List.of(
                                new BatchItemFailure(item.caseId(), code, e.getMessage()));
                        log.warn("[CASE-BATCH] batchId={} 整批拒绝：caseId={} {} {}",
                                batchId, item.caseId(), code, e.getMessage());
                        throw new BatchDispositionRejectedException(batchId, failure);
                    }
                }
                // 4) 全部合法：逐项生效（复用单笔处置的审计留痕）
                List<String> applied = new ArrayList<>();
                for (DispositionRequest item : request.items()) {
                    ReviewCase target = targets.get(item.caseId());
                    CaseStatus to = stateMachine.transition(target.getStatus(), item);
                    mutate(target, item, to);
                    applied.add(target.getCaseId());
                }
                BatchDispositionResult result = new BatchDispositionResult(batchId, false,
                        List.copyOf(applied), Instant.now(clock));
                batchResults.put(batchId, result);
                log.info("[CASE-BATCH] batchId={} 整批生效：案件={}", batchId, applied);
                return result;
            } finally {
                unlockAll(locked);
            }
        }
    }

    /** 按列表顺序（调用方保证 caseId 升序）依次加锁，避免死锁。 */
    private static void lockAll(List<ReviewCase> cases) {
        for (ReviewCase c : cases) {
            c.getLock().lock();
        }
    }

    /** 按相反顺序解锁。 */
    private static void unlockAll(List<ReviewCase> cases) {
        for (int k = cases.size() - 1; k >= 0; k--) {
            cases.get(k).getLock().unlock();
        }
    }

    /** 在案件锁内执行状态变更与审计留痕（单笔与批量共用的生效逻辑）。 */
    private void mutate(ReviewCase target, DispositionRequest request, CaseStatus to) {
        CaseStatus from = target.getStatus();
        Instant now = Instant.now(clock);
        target.setStatus(to);
        if (request.action() == CaseAction.ASSIGN) {
            target.setAssignee(request.reviewer());
        }
        target.setUpdatedAt(now);
        target.appendEvent(new CaseEvent(target.getCaseId(), from, to,
                request.reviewer(), request.role(), request.note(), now));
        log.info("[CASE] 处置 caseId={} action={} {}->{} reviewer={} role={} note={}",
                target.getCaseId(), request.action(), from, to,
                request.reviewer(), request.role(), request.note());
    }

    public ReviewCase get(String caseId) {
        ReviewCase c = casesById.get(caseId);
        if (c == null) {
            throw new CaseNotFoundException(caseId);
        }
        return c;
    }

    public Optional<ReviewCase> findByTransactionId(String transactionId) {
        return Optional.ofNullable(txToCase.get(transactionId)).map(casesById::get);
    }

    public List<ReviewCase> all() {
        return List.copyOf(casesById.values());
    }

    public List<CaseEvent> events(String caseId) {
        return get(caseId).getEvents();
    }

    /** 清空内存案件（供模拟器/测试隔离不同到达方式时使用）。 */
    public void clear() {
        casesById.clear();
        txToCase.clear();
        batchResults.clear();
        sequence.set(0);
    }

    private String nextCaseId() {
        return String.format("CASE-%06d", sequence.incrementAndGet());
    }
}
