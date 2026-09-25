package com.github.xingjianluo.creditriskscoringenginejava.service;

import com.github.xingjianluo.creditriskscoringenginejava.domain.CaseAction;
import com.github.xingjianluo.creditriskscoringenginejava.domain.CaseStatus;
import com.github.xingjianluo.creditriskscoringenginejava.domain.ReviewCase;
import com.github.xingjianluo.creditriskscoringenginejava.domain.ReviewError;
import com.github.xingjianluo.creditriskscoringenginejava.domain.Role;
import com.github.xingjianluo.creditriskscoringenginejava.domain.ScoreResult;
import com.github.xingjianluo.creditriskscoringenginejava.domain.TransitionRecord;
import com.github.xingjianluo.creditriskscoringenginejava.store.CaseStore;
import com.github.xingjianluo.creditriskscoringenginejava.web.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 审查案件服务。
 *
 * <p>状态机（非法迁移一律拒绝并返回 ILLEGAL_TRANSITION）：
 * <pre>
 *   OPEN      --CLAIM-->     IN_REVIEW
 *   OPEN      --DISMISS-->   DISMISSED   (SENIOR，需原因)
 *   IN_REVIEW --ESCALATE-->  ESCALATED
 *   IN_REVIEW --CLOSE-->     CLOSED      (需原因)
 *   IN_REVIEW --DISMISS-->   DISMISSED   (SENIOR，需原因)
 *   ESCALATED --CLOSE-->     CLOSED      (SENIOR，需原因)
 *   ESCALATED --DISMISS-->   DISMISSED   (SENIOR，需原因)
 * </pre>
 * CLOSED / DISMISSED 为终态；任何来自终态的迁移均为非法。
 * 权限不足返回 FORBIDDEN_ROLE；CLOSE/DISMISS 必须填写原因，否则 REASON_REQUIRED。
 */
@Service
public class CaseService {

    private static final Logger log = LoggerFactory.getLogger(CaseService.class);

    private record Allowed(CaseStatus to, Set<Role> roles, boolean reasonRequired) {
    }

    private static final Map<CaseStatus, Map<CaseAction, Allowed>> TRANSITIONS =
            new EnumMap<>(CaseStatus.class);
    private static final Set<Role> ANY = EnumSet.allOf(Role.class);
    private static final Set<Role> SENIOR_ONLY = EnumSet.of(Role.SENIOR);

    static {
        put(CaseStatus.OPEN, CaseAction.CLAIM, CaseStatus.IN_REVIEW, ANY, false);
        put(CaseStatus.OPEN, CaseAction.DISMISS, CaseStatus.DISMISSED, SENIOR_ONLY, true);
        put(CaseStatus.IN_REVIEW, CaseAction.ESCALATE, CaseStatus.ESCALATED, ANY, false);
        put(CaseStatus.IN_REVIEW, CaseAction.CLOSE, CaseStatus.CLOSED, ANY, true);
        put(CaseStatus.IN_REVIEW, CaseAction.DISMISS, CaseStatus.DISMISSED, SENIOR_ONLY, true);
        put(CaseStatus.ESCALATED, CaseAction.CLOSE, CaseStatus.CLOSED, SENIOR_ONLY, true);
        put(CaseStatus.ESCALATED, CaseAction.DISMISS, CaseStatus.DISMISSED, SENIOR_ONLY, true);
    }

    private static void put(CaseStatus from, CaseAction action, CaseStatus to,
                            Set<Role> roles, boolean reasonRequired) {
        TRANSITIONS.computeIfAbsent(from, k -> new EnumMap<>(CaseAction.class))
                .put(action, new Allowed(to, roles, reasonRequired));
    }

    private final CaseStore caseStore;
    private final AtomicInteger sequence = new AtomicInteger();

    public CaseService(CaseStore caseStore) {
        this.caseStore = caseStore;
    }

    /** 按可疑流水幂等开案；重复触发返回既有案件。 */
    public ReviewCase openFor(ScoreResult result) {
        String caseId = "CASE-" + String.format("%06d", sequence.incrementAndGet());
        ReviewCase created = new ReviewCase(caseId, result.transactionId(),
                result.accountId(), result.totalScore(), result.rulesVersion(),
                result.scoredAt());
        ReviewCase outcome = caseStore.createIfAbsent(created);
        if (outcome == created) {
            log.info("[案件创建] caseId={} txnId={} score={} version={}",
                    caseId, result.transactionId(), result.totalScore(),
                    result.rulesVersion());
        } else {
            log.info("[案件幂等] txnId={} 已存在案件 caseId={}，不重复开案",
                    result.transactionId(), outcome.getCaseId());
        }
        return outcome;
    }

    public ReviewCase get(String caseId) {
        return caseStore.findByCaseId(caseId)
                .orElseThrow(() -> new ApiException(ReviewError.CASE_NOT_FOUND,
                        "caseId=" + caseId));
    }

    public Optional<ReviewCase> findByTransactionId(String transactionId) {
        return caseStore.findByTransactionId(transactionId);
    }

    public List<ReviewCase> list() {
        return caseStore.all();
    }

    /**
     * 执行状态迁移。校验顺序：案件存在 → 迁移合法 → 角色权限 → 原因必填，
     * 每一步都有可区分的错误码。整个迁移在案件对象级别串行化。
     */
    public ReviewCase transition(String caseId, CaseAction action, Role role,
                                 String actor, String reason) {
        ReviewCase target = get(caseId);
        if (action == null || role == null) {
            throw new ApiException(ReviewError.ILLEGAL_TRANSITION,
                    "action 与 role 不能为空");
        }
        synchronized (target) {
            CaseStatus from = target.getStatus();
            Allowed allowed = TRANSITIONS.getOrDefault(from, Map.of()).get(action);
            if (allowed == null) {
                throw new ApiException(ReviewError.ILLEGAL_TRANSITION,
                        "状态 " + from + " 不允许动作 " + action);
            }
            if (!allowed.roles().contains(role)) {
                throw new ApiException(ReviewError.FORBIDDEN_ROLE,
                        "角色 " + role + " 无权在状态 " + from + " 执行 " + action
                                + "，允许角色 " + allowed.roles());
            }
            if (allowed.reasonRequired() && (reason == null || reason.isBlank())) {
                throw new ApiException(ReviewError.REASON_REQUIRED,
                        action + " 必须提供非空原因");
            }

            CaseStatus to = allowed.to();
            target.getHistory().add(new TransitionRecord(Instant.now(), from, to,
                    action, role, actor, reason));
            target.setStatus(to);
            if (to == CaseStatus.CLOSED || to == CaseStatus.DISMISSED) {
                target.setCloseReason(reason);
            }
            if (action == CaseAction.CLAIM) {
                target.setAssignee(actor);
            }
            log.info("[状态流转] caseId={} {} --{}--> {} actor={}({}) reason={}",
                    caseId, from, action, to, actor, role, reason);
            return target;
        }
    }
}
