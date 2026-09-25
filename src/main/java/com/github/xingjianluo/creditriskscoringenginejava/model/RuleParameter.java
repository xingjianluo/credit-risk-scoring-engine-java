package com.github.xingjianluo.creditriskscoringenginejava.model;

/**
 * 单个规则参数（阈值类参数），按版本保存。
 */
public record RuleParameter(String code, String name, String value) {
}
