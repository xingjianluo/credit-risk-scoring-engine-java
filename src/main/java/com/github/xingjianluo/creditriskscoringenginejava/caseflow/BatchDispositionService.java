package com.github.xingjianluo.creditriskscoringenginejava.caseflow;

import com.github.xingjianluo.creditriskscoringenginejava.exception.BatchDispositionRejectedException;
import com.github.xingjianluo.creditriskscoringenginejava.exception.InvalidRequestException;
import com.github.xingjianluo.creditriskscoringenginejava.model.BatchDispositionReport;
import com.github.xingjianluo.creditriskscoringenginejava.model.BatchDispositionRequest;
import com.github.xingjianluo.creditriskscoringenginejava.model.CaseEvent;
import com.github.xingjianluo.creditriskscoringenginejava.model.CaseStatus;
import com.github.xingjianluo.creditriskscoringenginejava.model.DispositionItemResult;
import com.github.xingjianluo.creditriskscoringenginejava.model.DispositionRequest;
import com.github.xingjianluo.creditriskscoringenginejava.model.ReviewCase;
import com.github.xingjianluo.creditriskscoringenginejava.model.ReviewerRole;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;

/**
 * 案件批量处置服务：对同一账户下的一批案件统一应用一个处置动作。
 *
 * <p>语义：
 * <ul>
 *   <li><b>幂等</b>：batchId 为批次幂等键，重复提交返回首次报告（duplicate=true），
 *       不产生重复处置记录；</li>
 *   <li><b>整批原子</b>：先对全部案件做存在性/账户归属/权限/状态迁移校验，
 *       任一不满足则整批拒绝（{@link BatchDispositionRejectedException} 携带逐条
 *       可区分原因），不做任何状态变更、不留处置事件；全部通过才统一应用；</li>
 *   <li><b>并发等价串行</b>：批次键串行化幂等；案件按 caseId 排序后依次加锁，
 *       并发批次与单笔处置的最终状态和审计链与某种串行执行一致；</li>
 *   <li>状态迁移与权限规则完全复用 {@link CaseStateMachine}，终态保护不变。</li>
 * </ul>
 */
@Service
public class BatchDispositionService {

    private static final Logger log = LoggerFactory.getLogger(BatchDispositionService.class);

    private final ReviewCaseService caseService;
    private final CaseStateMachine stateMachine;
    private final Clock clock;

    /** batchId -> 首次处理报告（幂等回放用）。 */
    private final ConcurrentMap<String, BatchDispositionReport> reports = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Object> batchLocks = new ConcurrentHashMap<>();

    public BatchDispositionService(ReviewCaseService caseService, CaseStateMachine stateMachine,
                                   Clock clock) {
        this.caseService = caseService;
        this.stateMachine = stateMachine;
        this.clock = clock;
    }

    public BatchDispositionReport applyBatch(BatchDispositionRequest request) {
        validateShape(request);
        // 同一批次内重复 caseId 折叠，避免对同一案件重复迁移
        List<String> caseIds = request.caseIds().stream().distinct().toList();
        synchronized (batchLocks.computeIfAbsent(request.batchId(), k -> new Object())) {
            BatchDispositionReport existing = reports.get(request.batchId());
            if (existing != null) {
                log.info("[BATCH-DISP] batchId={} 重复提交，返回首次报告（applied={}），不重复处置",
                        request.batchId(), existing.applied());
                return asDuplicate(existing);
            }
            BatchDispositionReport report = execute(request, caseIds);
            reports.put(request.batchId(), report);
            return report;
        }
    }

    private BatchDispositionReport execute(BatchDispositionRequest request, List<String> caseIds) {
        List<DispositionItemResult> failures = new ArrayList<>();
        List<ReviewCase> targets = new ArrayList<>();

        // 1) 存在性 / 账户归属 / 权限校验（不依赖案件当前状态的部分）
        for (String caseId : caseIds) {
            Optional<ReviewCase> found = caseService.find(caseId);
            if (found.isEmpty()) {
                failures.add(DispositionItemResult.failed(caseId, "CASE_NOT_FOUND",
                        "审查案件不存在: " + caseId, null));
                continue;
            }
            ReviewCase c = found.get();
            if (!c.getAccountId().equals(request.accountId())) {
                failures.add(DispositionItemResult.failed(caseId, "ACCOUNT_MISMATCH",
                        "案件 " + caseId + " 属于账户 " + c.getAccountId()
                                + "，与批次账户 " + request.accountId() + " 不一致", null));
                continue;
            }
            ReviewerRole required = stateMachine.requiredRole(request.action());
            if (request.role() == null || !request.role().atLeast(required)) {
                failures.add(DispositionItemResult.failed(caseId, "CASE_PERMISSION_DENIED",
                        "权限不足: 动作=" + request.action() + ", 需要角色>=" + required
                                + ", 实际角色=" + request.role(), null));
                continue;
            }
            targets.add(c);
        }

        // 2) 按 caseId 排序加锁（全局统一顺序避免死锁），在锁内校验状态迁移
        List<ReviewCase> sorted = targets.stream()
                .sorted(java.util.Comparator.comparing(ReviewCase::getCaseId)).toList();
        return withLocks(sorted, 0, () -> {
            Map<String, CaseStatus> planned = new LinkedHashMap<>();
            for (ReviewCase c : sorted) {
                DispositionRequest perCase = new DispositionRequest(c.getCaseId(),
                        request.action(), request.reviewer(), request.role(), request.note());
                try {
                    CaseStatus to = stateMachine.transition(c, perCase);
                    planned.put(c.getCaseId(), to);
                } catch (com.github.xingjianluo.creditriskscoringenginejava.exception
                                 .IllegalCaseTransitionException e) {
                    failures.add(DispositionItemResult.failed(c.getCaseId(),
                            "ILLEGAL_CASE_TRANSITION", e.getMessage(), c.getStatus()));
                }
            }
            // 3) 任一失败 → 整批拒绝，不做任何变更
            if (!failures.isEmpty()) {
                log.warn("[BATCH-DISP] batchId={} 整批拒绝：失败 {} 条，原因={}",
                        request.batchId(), failures.size(),
                        failures.stream().map(f -> f.caseId() + ":" + f.errorCode()).toList());
                throw new BatchDispositionRejectedException(request.batchId(), failures);
            }
            // 4) 全部合法 → 统一应用并留痕（事件带 batchId）
            Instant now = Instant.now(clock);
            List<DispositionItemResult> items = new ArrayList<>();
            for (ReviewCase c : sorted) {
                CaseStatus from = c.getStatus();
                CaseStatus to = planned.get(c.getCaseId());
                c.setStatus(to);
                if (request.action() == com.github.xingjianluo.creditriskscoringenginejava.model
                        .CaseAction.ASSIGN) {
                    c.setAssignee(request.reviewer());
                }
                c.setUpdatedAt(now);
                c.appendEvent(new CaseEvent(c.getCaseId(), from, to, request.reviewer(),
                        request.role(), request.note(), now, request.batchId()));
                items.add(DispositionItemResult.applied(c.getCaseId(), from, to));
                log.info("[BATCH-DISP] batchId={} 处置 caseId={} action={} {}->{} reviewer={} role={}",
                        request.batchId(), c.getCaseId(), request.action(), from, to,
                        request.reviewer(), request.role());
            }
            log.info("[BATCH-DISP] batchId={} 整批完成：应用 {} 条", request.batchId(), items.size());
            return new BatchDispositionReport(request.batchId(), request.accountId(),
                    request.action(), false, items.size(), List.copyOf(items), now);
        });
    }

    /** 按顺序嵌套持有全部案件锁执行 body（sorted 已按 caseId 排序，保证无死锁）。 */
    private <T> T withLocks(List<ReviewCase> sorted, int index, Supplier<T> body) {
        if (index >= sorted.size()) {
            return body.get();
        }
        synchronized (sorted.get(index)) {
            return withLocks(sorted, index + 1, body);
        }
    }

    private BatchDispositionReport asDuplicate(BatchDispositionReport first) {
        return new BatchDispositionReport(first.batchId(), first.accountId(), first.action(),
                true, first.applied(), first.items(), first.processedAt());
    }

    private void validateShape(BatchDispositionRequest request) {
        if (request == null) {
            throw new InvalidRequestException("INVALID_BATCH_DISPOSITION_REQUEST", "批量处置请求缺失");
        }
        if (request.batchId() == null || request.batchId().isBlank()) {
            throw new InvalidRequestException("INVALID_BATCH_DISPOSITION_REQUEST",
                    "批量处置请求缺失 batchId（幂等键）");
        }
        if (request.accountId() == null || request.accountId().isBlank()) {
            throw new InvalidRequestException("INVALID_BATCH_DISPOSITION_REQUEST",
                    "批量处置请求缺失 accountId");
        }
        if (request.caseIds() == null || request.caseIds().isEmpty()) {
            throw new InvalidRequestException("INVALID_BATCH_DISPOSITION_REQUEST",
                    "批量处置请求 caseIds 为空");
        }
        if (request.action() == null) {
            throw new InvalidRequestException("INVALID_BATCH_DISPOSITION_REQUEST",
                    "批量处置请求缺失 action");
        }
    }

    /** 清空批次记录（供模拟器/测试隔离使用）。 */
    public void clear() {
        reports.clear();
        batchLocks.clear();
    }
}
