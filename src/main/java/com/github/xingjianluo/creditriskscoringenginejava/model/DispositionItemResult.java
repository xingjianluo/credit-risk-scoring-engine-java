package com.github.xingjianluo.creditriskscoringenginejava.model;

/**
 * 批量处置中单个案件的处理结果（成功记录迁移，失败给出可区分原因码）。
 */
public record DispositionItemResult(
        String caseId,
        boolean applied,
        String errorCode,
        String message,
        CaseStatus from,
        CaseStatus to) {

    public static DispositionItemResult applied(String caseId, CaseStatus from, CaseStatus to) {
        return new DispositionItemResult(caseId, true, null, null, from, to);
    }

    public static DispositionItemResult failed(String caseId, String errorCode, String message,
                                               CaseStatus from) {
        return new DispositionItemResult(caseId, false, errorCode, message, from, null);
    }
}
