package com.github.xingjianluo.creditriskscoringenginejava.domain;

/**
 * 流水提交的统一结果：SCORED（新评分/重复读取同一条结论）、REJECTED（可区分拒绝原因）。
 */
public record SubmissionOutcome(
        boolean duplicate,
        ScoreResult scoreResult,
        RejectedSubmission rejection
) {
    public static SubmissionOutcome scored(ScoreResult result, boolean duplicate) {
        return new SubmissionOutcome(duplicate, result, null);
    }

    public static SubmissionOutcome rejected(RejectedSubmission rejection) {
        return new SubmissionOutcome(false, null, rejection);
    }

    public boolean isRejected() {
        return rejection != null;
    }
}
