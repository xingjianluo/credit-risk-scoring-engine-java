package com.github.xingjianluo.creditriskscoringenginejava.domain;

/**
 * 审查案件状态。CLOSED 与 DISMISSED 为终态。
 */
public enum CaseStatus {
    OPEN,
    IN_REVIEW,
    ESCALATED,
    CLOSED,
    DISMISSED
}
