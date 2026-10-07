package com.github.xingjianluo.creditriskscoringenginejava.exception;

import com.github.xingjianluo.creditriskscoringenginejava.model.BatchItemFailure;

import java.util.List;

/**
 * 批量处置被整批拒绝：批次中至少一项权限不足 / 非法迁移 / 案件不存在。
 * 携带逐项可区分失败原因；抛出时保证整批无任何状态变更与审计事件。
 */
public class BatchDispositionRejectedException extends RuntimeException {

    private final String batchId;
    private final List<BatchItemFailure> failures;

    public BatchDispositionRejectedException(String batchId, List<BatchItemFailure> failures) {
        super("批量处置被拒绝 batchId=" + batchId + "，失败项=" + failures);
        this.batchId = batchId;
        this.failures = List.copyOf(failures);
    }

    public String batchId() {
        return batchId;
    }

    public List<BatchItemFailure> failures() {
        return failures;
    }

    /** 全部失败项均为权限不足时返回 true（HTTP 映射为 403，否则 409）。 */
    public boolean allPermissionDenied() {
        return !failures.isEmpty()
                && failures.stream().allMatch(f -> "CASE_PERMISSION_DENIED".equals(f.errorCode()));
    }
}
