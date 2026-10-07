package com.github.xingjianluo.creditriskscoringenginejava.model;

import java.time.Instant;
import java.util.List;

/**
 * 批量处置结果：整批生效（all-or-nothing），重复提交返回 duplicate=true 的首次结果。
 */
public record BatchDispositionResult(
        String batchId,
        boolean duplicate,
        List<String> appliedCaseIds,
        Instant appliedAt) {

    public BatchDispositionResult asDuplicate() {
        return new BatchDispositionResult(batchId, true, appliedCaseIds, appliedAt);
    }
}
