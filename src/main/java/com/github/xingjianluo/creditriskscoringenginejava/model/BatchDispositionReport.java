package com.github.xingjianluo.creditriskscoringenginejava.model;

import java.time.Instant;
import java.util.List;

/**
 * 批量处置报告：整批要么全部应用，要么整体拒绝（此时抛出异常而非返回本报告）。
 * 同一 batchId 重复提交返回首次报告且 duplicate=true。
 */
public record BatchDispositionReport(
        String batchId,
        String accountId,
        CaseAction action,
        boolean duplicate,
        int applied,
        List<DispositionItemResult> items,
        Instant processedAt) {
}
