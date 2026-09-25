package com.github.xingjianluo.creditriskscoringenginejava.exception;

import com.github.xingjianluo.creditriskscoringenginejava.model.CaseStatus;

/**
 * 非法状态迁移。错误码可区分于权限不足。
 */
public class IllegalCaseTransitionException extends RuntimeException {
    public IllegalCaseTransitionException(CaseStatus from, String action) {
        super("非法状态迁移: 当前状态=" + from + ", 动作=" + action);
    }
}
