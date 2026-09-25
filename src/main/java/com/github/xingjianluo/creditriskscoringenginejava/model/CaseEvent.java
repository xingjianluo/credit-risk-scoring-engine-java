package com.github.xingjianluo.creditriskscoringenginejava.model;

import java.time.Instant;

/**
 * 案件状态机流转事件（审计留痕）。
 */
public record CaseEvent(
        String caseId,
        CaseStatus from,
        CaseStatus to,
        String reviewer,
        ReviewerRole role,
        String note,
        Instant at) {
}
