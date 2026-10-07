package com.github.xingjianluo.creditriskscoringenginejava.web;

import com.github.xingjianluo.creditriskscoringenginejava.exception.BatchDispositionRejectedException;
import com.github.xingjianluo.creditriskscoringenginejava.exception.CaseNotFoundException;
import com.github.xingjianluo.creditriskscoringenginejava.exception.CasePermissionDeniedException;
import com.github.xingjianluo.creditriskscoringenginejava.exception.IllegalCaseTransitionException;
import com.github.xingjianluo.creditriskscoringenginejava.exception.RuleVersionNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * 以可区分错误码返回拒绝/异常原因。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

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

    /**
     * 批量处置整批拒绝：逐项给出可区分原因（权限不足/非法迁移/案件不存在）。
     * 全部为权限不足时返回 403，否则 409；整批无任何状态变更。
     */
    @ExceptionHandler(BatchDispositionRejectedException.class)
    public ResponseEntity<Map<String, Object>> batchRejected(BatchDispositionRejectedException e) {
        HttpStatus status = e.allPermissionDenied() ? HttpStatus.FORBIDDEN : HttpStatus.CONFLICT;
        return ResponseEntity.status(status)
                .body(Map.of("errorCode", "BATCH_DISPOSITION_REJECTED",
                        "batchId", e.batchId(),
                        "message", e.getMessage(),
                        "failures", e.failures()));
    }

    /** 请求体 JSON 无法解析（字段类型错误/格式损坏）：可区分 400，不抛系统级错误。 */
    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, String>> malformedBody(
            org.springframework.http.converter.HttpMessageNotReadableException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("errorCode", "MALFORMED_REQUEST_BODY",
                        "message", "请求体无法解析: " + e.getMostSpecificCause().getMessage()));
    }

    /** 参数取值非法（如模拟到达方式不存在）：可区分 400。 */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badArgument(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("errorCode", "INVALID_ARGUMENT",
                        "message", e.getMessage() == null ? "参数非法" : e.getMessage()));
    }

    /** 兜底：任何未预期异常都返回可区分错误码，绝不返回不透明失败。 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> unexpected(Exception e) {
        org.slf4j.LoggerFactory.getLogger(GlobalExceptionHandler.class)
                .error("[ERROR] 未预期异常 {}", e.getMessage(), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("errorCode", "INTERNAL_ERROR",
                        "message", String.valueOf(e.getMessage())));
    }
}
