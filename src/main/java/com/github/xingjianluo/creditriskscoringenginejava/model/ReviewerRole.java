package com.github.xingjianluo.creditriskscoringenginejava.model;

/**
 * 审查人员角色（权限层级 ANALYST &lt; SENIOR &lt; ADMIN）。
 */
public enum ReviewerRole {
    ANALYST,
    SENIOR,
    ADMIN;

    public boolean atLeast(ReviewerRole other) {
        return this.ordinal() >= other.ordinal();
    }
}
