package com.github.xingjianluo.creditriskscoringenginejava.domain;

/**
 * 案件/规则管理操作的可区分错误码（携带 HTTP 状态码）。
 */
public enum ReviewError {
    CASE_NOT_FOUND(404, "案件不存在"),
    TRANSACTION_NOT_FOUND(404, "流水不存在或尚未被处理"),
    ILLEGAL_TRANSITION(409, "非法状态迁移"),
    FORBIDDEN_ROLE(403, "当前角色无权执行该处置"),
    REASON_REQUIRED(400, "该处置必须填写原因"),
    RULE_VERSION_NOT_FOUND(404, "规则版本不存在"),
    INVALID_RULE_DEFINITION(400, "规则定义非法");

    private final int httpStatus;
    private final String message;

    ReviewError(int httpStatus, String message) {
        this.httpStatus = httpStatus;
        this.message = message;
    }

    public int getHttpStatus() {
        return httpStatus;
    }

    public String getMessage() {
        return message;
    }
}
