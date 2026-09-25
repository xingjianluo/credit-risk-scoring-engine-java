package com.github.xingjianluo.creditriskscoringenginejava.model;

import java.time.Instant;
import java.util.Set;

/**
 * 评分时使用的账户历史基线快照（随评分结果持久化，保证可复现回溯）。
 */
public record BaselineSnapshot(
        String accountId,
        Instant asOf,
        int priorCount,
        String avgAmount,
        int recentWindowCount,
        Set<String> knownCounterparties) {
}
