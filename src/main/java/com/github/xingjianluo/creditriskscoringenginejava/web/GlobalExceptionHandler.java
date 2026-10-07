package com.github.xingjianluo.creditriskscoringenginejava.web;

import com.github.xingjianluo.creditriskscoringenginejava.exception.BatchDispositionRejectedException;
import com.github.xingjianluo.creditriskscoringenginejava.exception.CaseNotFoundException;
import com.github.xingjianluo.creditriskscoringenginejava.exception.CasePermissionDeniedException;
import com.github.xingjianluo.creditriskscoringenginejava.exception.IllegalCaseTransitionException;
import com.github.xingjianluo.creditriskscoringenginejava.exception.InvalidRequestException;
import com.github.xingjianluo.creditriskscoringenginejava.exception.RuleVersionNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * 以可区分错误码返回拒绝/异常原因。
 *
 * <p>统一契约：任何入口的输入异常都映射为 4xx + errorCode，
 * 绝不把框架/系统级堆栈直接暴露给调用方。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(RuleVersionNotFoundException.class)
    public ResponseEntity<Map<String, String>> ruleVersionNotFound(RuleVersionNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("errorCode", "RULE_VERSION_NOT_FOUND", "message", e.getMessage()));
    }

    @ExceptionHandler(CaseNotFoundException.class)
    public ResponseEntity<Map<String, String>> caseNotFound(CaseNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("errorCode", "CASE_NOT_FOUND", "message", e.getMessage()));
    }

    @ExceptionHandler(IllegalCaseTransitionException.class)
    public ResponseEntity<Map<String, String>> illegalTransition(IllegalCaseTransitionException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("errorCode", "ILLEGAL_CASE_TRANSITION", "message", e.getMessage()));
    }

    @ExceptionHandler(CasePermissionDeniedException.class)
    public ResponseEntity<Map<String, String>> permissionDenied(CasePermissionDeniedException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("errorCode", "CASE_PERMISSION_DENIED", "message", e.getMessage()));
    }

    /** 请求体/参数本身非法：400 + 可区分 errorCode。 */
    @ExceptionHandler(InvalidRequestException.class)
    public ResponseEntity<Map<String, String>> invalidRequest(InvalidRequestException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("errorCode", e.errorCode(), "message", e.getMessage()));
    }

    /** 批量处置整批拒绝：逐条可区分原因；纯权限问题 403，其余 409。 */
    @ExceptionHandler(BatchDispositionRejectedException.class)
    public ResponseEntity<Map<String, Object>> batchRejected(BatchDispositionRejectedException e) {
        HttpStatus status = e.allPermissionDenied() ? HttpStatus.FORBIDDEN : HttpStatus.CONFLICT;
        return ResponseEntity.status(status)
                .body(Map.of("errorCode", "BATCH_DISPOSITION_REJECTED",
                        "message", e.getMessage(),
                        "batchId", e.batchId(),
                        "failures", e.failures()));
    }

    /** 请求体 JSON 无法解析（格式错误 / 字段类型不匹配）：400 而非 500。 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, String>> malformedBody(HttpMessageNotReadableException e) {
        log.warn("[HTTP-400] 请求体无法解析: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("errorCode", "MALFORMED_REQUEST_BODY", "message", "请求体无法解析"));
    }

    /** 兜底：未预期的系统错误统一为不透明的 500，并完整记录日志。 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> internalError(Exception e) {
        log.error("[HTTP-500] 未预期错误", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("errorCode", "INTERNAL_ERROR", "message", "系统内部错误"));
    }
}
