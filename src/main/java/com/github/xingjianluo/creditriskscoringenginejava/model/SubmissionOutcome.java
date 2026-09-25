package com.github.xingjianluo.creditriskscoringenginejava.model;

import java.util.List;

/**
 * 单条流水提交结果：评分成功或拒绝，二选一。
 */
public record SubmissionOutcome(
        String transactionId,
        boolean accepted,
        RiskScoreResult score,
        RejectionRecord rejection,
        boolean duplicate,
        List<String> caseIds) {

    public static SubmissionOutcome scored(RiskScoreResult score, boolean duplicate, List<String> caseIds) {
        return new SubmissionOutcome(score.transactionId(), true, score, null, duplicate, List.copyOf(caseIds));
    }

    public static SubmissionOutcome rejected(RejectionRecord rejection, boolean duplicate) {
        return new SubmissionOutcome(rejection.transactionId(), false, null, rejection, duplicate, List.of());
    }
}
