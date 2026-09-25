package com.github.xingjianluo.creditriskscoringenginejava.exception;

public class CaseNotFoundException extends RuntimeException {
    public CaseNotFoundException(String caseId) {
        super("审查案件不存在: " + caseId);
    }
}
