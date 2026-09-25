package com.github.xingjianluo.creditriskscoringenginejava.model;

import java.util.List;

/**
 * 一批本地模拟事件的处理报告：用于校验不同到达方式下结果一致。
 */
public record BatchReport(
        String runId,
        ArrivalMode mode,
        int inputs,
        int uniqueTransactions,
        int accepted,
        int rejected,
        int duplicates,
        int casesOpened,
        List<SubmissionOutcome> outcomes) {
}
