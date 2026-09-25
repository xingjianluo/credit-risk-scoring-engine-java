package com.github.xingjianluo.creditriskscoringenginejava.caseflow;

import com.github.xingjianluo.creditriskscoringenginejava.exception.CasePermissionDeniedException;
import com.github.xingjianluo.creditriskscoringenginejava.exception.IllegalCaseTransitionException;
import com.github.xingjianluo.creditriskscoringenginejava.model.CaseAction;
import com.github.xingjianluo.creditriskscoringenginejava.model.CaseStatus;
import com.github.xingjianluo.creditriskscoringenginejava.model.DispositionRequest;
import com.github.xingjianluo.creditriskscoringenginejava.model.ReviewCase;
import com.github.xingjianluo.creditriskscoringenginejava.model.ReviewerRole;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 案件状态机：定义合法迁移与每个动作所需最低角色。
 *
 * <pre>
 * OPEN/REOPENED/INVESTIGATING --(START_INVESTIGATION, ANALYST)--> INVESTIGATING
 * OPEN/INVESTIGATING/REOPENED --(RESOLVE, SENIOR)--> RESOLVED
 * RESOLVED --(REOPEN, SENIOR)--> REOPENED
 * 任意状态 --(ASSIGN, ANALYST)--> 状态不变（仅记录分派）
 * </pre>
 *
 * <p>非法迁移抛 {@link IllegalCaseTransitionException}（HTTP 409），
 * 权限不足抛 {@link CasePermissionDeniedException}（HTTP 403），二者可区分。
 */
@Component
public class CaseStateMachine {

    private final Map<CaseAction, ReviewerRole> requiredRoles = new EnumMap<>(CaseAction.class);
    private final Map<CaseStatus, Set<CaseAction>> allowed = new EnumMap<>(CaseStatus.class);

    public CaseStateMachine() {
        requiredRoles.put(CaseAction.ASSIGN, ReviewerRole.ANALYST);
        requiredRoles.put(CaseAction.START_INVESTIGATION, ReviewerRole.ANALYST);
        requiredRoles.put(CaseAction.RESOLVE, ReviewerRole.SENIOR);
        requiredRoles.put(CaseAction.REOPEN, ReviewerRole.SENIOR);

        allowed.put(CaseStatus.OPEN, EnumSet.of(CaseAction.ASSIGN,
                CaseAction.START_INVESTIGATION, CaseAction.RESOLVE));
        allowed.put(CaseStatus.INVESTIGATING, EnumSet.of(CaseAction.ASSIGN,
                CaseAction.START_INVESTIGATION, CaseAction.RESOLVE));
        allowed.put(CaseStatus.REOPENED, EnumSet.of(CaseAction.ASSIGN,
                CaseAction.START_INVESTIGATION, CaseAction.RESOLVE));
        allowed.put(CaseStatus.RESOLVED, EnumSet.of(CaseAction.ASSIGN, CaseAction.REOPEN));
    }

    public ReviewerRole requiredRole(CaseAction action) {
        return requiredRoles.get(action);
    }

    /** 校验并执行迁移；非法迁移 / 权限不足分别抛出可区分异常。返回迁移后的状态。 */
    public CaseStatus transition(ReviewCase target, DispositionRequest request) {
        CaseStatus from = target.getStatus();
        CaseAction action = request.action();

        // 先校验权限，再校验迁移合法性，保证两类拒绝原因稳定可区分
        ReviewerRole required = requiredRoles.get(action);
        if (required == null || request.role() == null || !request.role().atLeast(required)) {
            throw new CasePermissionDeniedException(action,
                    required == null ? ReviewerRole.ADMIN : required, request.role());
        }
        if (!allowed.getOrDefault(from, EnumSet.noneOf(CaseAction.class)).contains(action)) {
            throw new IllegalCaseTransitionException(from, action.name());
        }
        return switch (action) {
            case ASSIGN -> from;
            case START_INVESTIGATION -> CaseStatus.INVESTIGATING;
            case RESOLVE -> CaseStatus.RESOLVED;
            case REOPEN -> CaseStatus.REOPENED;
        };
    }
}
