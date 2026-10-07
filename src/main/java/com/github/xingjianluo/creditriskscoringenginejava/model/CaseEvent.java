package com.github.xingjianluo.creditriskscoringenginejava.model;

import java.time.Instant;

/**
 * 案件状态机流转事件（审计留痕）。
 *
 * @param batchId 批量处置批次号；单笔处置与系统事件为 null
 */
public record CaseEvent(
        String caseId,
        CaseStatus from,
        CaseStatus to,
        String reviewer,
        ReviewerRole role,
        String note,
        Instant at,
        String batchId) {

    /** 非批量场景（自动开案 / 单笔处置 / 归集关联）的便捷构造。 */
    public CaseEvent(String caseId, CaseStatus from, CaseStatus to,
                     String reviewer, ReviewerRole role, String note, Instant at) {
        this(caseId, from, to, reviewer, role, note, at, null);
    }
}
