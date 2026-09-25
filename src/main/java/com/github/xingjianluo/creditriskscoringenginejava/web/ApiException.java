package com.github.xingjianluo.creditriskscoringenginejava.web;

import com.github.xingjianluo.creditriskscoringenginejava.domain.ReviewError;

/**
 * 携带可区分错误码的业务异常，由全局异常处理转换为 HTTP 响应。
 */
public class ApiException extends RuntimeException {
    private final transient ReviewError error;

    public ApiException(ReviewError error) {
        super(error.getMessage());
        this.error = error;
    }

    public ApiException(ReviewError error, String detail) {
        super(error.getMessage() + ": " + detail);
        this.error = error;
    }

    public ReviewError getError() {
        return error;
    }
}
