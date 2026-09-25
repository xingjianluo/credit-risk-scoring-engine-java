package com.github.xingjianluo.creditriskscoringenginejava.domain;

import java.util.List;

/**
 * 本地场景模拟（重复/乱序/并发）的运行报告，用于可追溯核对。
 */
public record ScenarioReport(
        String scenario,
        int submissions,
        int distinctScores,
        int distinctCases,
        List<String> outcomes,
        boolean consistent
) {
}
