package com.github.xingjianluo.creditriskscoringenginejava.model;

/**
 * 批量处置中单个案件的失败原因（可区分：权限不足 / 非法迁移 / 案件不存在）。
 */
public record BatchItemFailure(
        String caseId,
        String errorCode,
        String message) {
}
