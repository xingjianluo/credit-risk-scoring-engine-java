package com.github.xingjianluo.creditriskscoringenginejava.exception;

/**
 * 请求本身非法（缺字段 / 非法枚举值 / 空请求体等）。
 * 以 400 + 可区分 errorCode 返回，而不是系统级 500。
 */
public class InvalidRequestException extends RuntimeException {
    private final String errorCode;

    public InvalidRequestException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public String errorCode() {
        return errorCode;
    }
}
