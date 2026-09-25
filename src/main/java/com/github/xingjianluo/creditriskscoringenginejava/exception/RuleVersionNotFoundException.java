package com.github.xingjianluo.creditriskscoringenginejava.exception;

public class RuleVersionNotFoundException extends RuntimeException {
    public RuleVersionNotFoundException(String version) {
        super("规则版本不存在: " + version);
    }
}
