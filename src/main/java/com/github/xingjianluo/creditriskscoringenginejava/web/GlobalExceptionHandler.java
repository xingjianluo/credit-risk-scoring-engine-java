package com.github.xingjianluo.creditriskscoringenginejava.web;

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
}
