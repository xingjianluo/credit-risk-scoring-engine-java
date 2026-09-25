package com.github.xingjianluo.creditriskscoringenginejava.model;

/**
 * 规则求值上下文：流水 + 其账户基线。
 */
public record RuleContext(Transaction tx, BaselineSnapshot baseline) {
}
