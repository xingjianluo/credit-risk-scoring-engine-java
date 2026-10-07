package com.github.xingjianluo.creditriskscoringenginejava.exception;

import com.github.xingjianluo.creditriskscoringenginejava.model.DispositionItemResult;

import java.util.List;

/**
 * 批量处置整体拒绝：批次中至少一条不满足权限或状态迁移约束，
 * 整批不应用任何变更，逐条给出可区分原因。
 */
public class BatchDispositionRejectedException extends RuntimeException {

    private final String batchId;
    private final List<DispositionItemResult> failures;

    public BatchDispositionRejectedException(String batchId, List<DispositionItemResult> failures) {
        super("批量处置被拒绝 batchId=" + batchId + "，失败 " + failures.size() + " 条");
        this.batchId = batchId;
        this.failures = List.copyOf(failures);
    }

    public String batchId() {
        return batchId;
    }

    public List<DispositionItemResult> failures() {
        return failures;
    }

    /** 全部失败都是权限不足时返回 true（用于映射 403，否则 409）。 */
    public boolean allPermissionDenied() {
        return !failures.isEmpty()
                && failures.stream().allMatch(f -> "CASE_PERMISSION_DENIED".equals(f.errorCode()));
    }
}
