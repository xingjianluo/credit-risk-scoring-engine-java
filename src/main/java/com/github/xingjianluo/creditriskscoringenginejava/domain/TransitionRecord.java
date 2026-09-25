package com.github.xingjianluo.creditriskscoringenginejava.domain;

import java.time.Instant;

/**
 * 案件状态流转的一条不可变审计记录。
 */
public record TransitionRecord(
        Instant at,
        CaseStatus from,
        CaseStatus to,
        CaseAction action,
        Role role,
        String actor,
        String reason
) {
}
