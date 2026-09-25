package com.github.xingjianluo.creditriskscoringenginejava.exception;

import com.github.xingjianluo.creditriskscoringenginejava.model.CaseAction;
import com.github.xingjianluo.creditriskscoringenginejava.model.ReviewerRole;

/**
 * 处置权限不足。错误码可区分于非法状态迁移。
 */
public class CasePermissionDeniedException extends RuntimeException {
    public CasePermissionDeniedException(CaseAction action, ReviewerRole required, ReviewerRole actual) {
        super("权限不足: 动作=" + action + ", 需要角色>=" + required + ", 实际角色=" + actual);
    }
}
