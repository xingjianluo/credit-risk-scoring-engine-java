package com.github.xingjianluo.creditriskscoringenginejava.model;

/**
 * 审查案件状态：OPEN -> INVESTIGATING -> RESOLVED，RESOLVED -> REOPENED。
 */
public enum CaseStatus {
    OPEN,
    INVESTIGATING,
    RESOLVED,
    REOPENED
}
