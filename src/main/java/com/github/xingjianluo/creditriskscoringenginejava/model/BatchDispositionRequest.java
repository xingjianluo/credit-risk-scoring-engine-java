package com.github.xingjianluo.creditriskscoringenginejava.model;

import java.util.List;

/**
 * 批量处置请求：对同一账户下的一批案件统一提交一个处置动作。
 *
 * @param batchId 批次幂等键：同一 batchId 重复提交返回首次处理报告，不重复处置
 */
public record BatchDispositionRequest(
        String batchId,
        String accountId,
        List<String> caseIds,
        CaseAction action,
        String reviewer,
        ReviewerRole role,
        String note) {
}
