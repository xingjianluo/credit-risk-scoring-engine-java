package com.github.xingjianluo.creditriskscoringenginejava.model;

import java.util.List;

/**
 * 案件批量处置请求：对一批案件统一提交处置动作。
 *
 * @param batchId 批次幂等键：同一 batchId 重复提交返回首次结果，不产生重复处置记录
 * @param items   逐项处置请求（案件 ID + 动作 + 审查人 + 角色 + 备注）
 */
public record BatchDispositionRequest(
        String batchId,
        List<DispositionRequest> items) {
}
