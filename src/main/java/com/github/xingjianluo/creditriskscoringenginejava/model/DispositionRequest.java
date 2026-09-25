package com.github.xingjianluo.creditriskscoringenginejava.model;

/**
 * 案件处置请求。
 */
public record DispositionRequest(
        String caseId,
        CaseAction action,
        String reviewer,
        ReviewerRole role,
        String note) {
}
