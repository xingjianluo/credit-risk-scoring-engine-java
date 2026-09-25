package com.github.xingjianluo.creditriskscoringenginejava.web;

import com.github.xingjianluo.creditriskscoringenginejava.domain.ReviewError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 将业务异常映射为带可区分错误码的 HTTP 响应。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorBody> handle(ApiException ex) {
        ReviewError error = ex.getError();
        log.warn("[业务拒绝] code={} message={}", error.name(), ex.getMessage());
        return ResponseEntity.status(error.getHttpStatus())
                .body(new ErrorBody(error.name(), ex.getMessage()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorBody> handleUnreadable(HttpMessageNotReadableException ex) {
        return ResponseEntity.badRequest()
                .body(new ErrorBody("MALFORMED_REQUEST", "请求体无法解析: " + ex.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorBody> handleOther(Exception ex) {
        log.error("[未预期异常]", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorBody("INTERNAL_ERROR", ex.getMessage()));
    }
}
