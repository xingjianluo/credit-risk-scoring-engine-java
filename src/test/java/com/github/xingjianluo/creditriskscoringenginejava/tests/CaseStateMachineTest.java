package com.github.xingjianluo.creditriskscoringenginejava.tests;

import com.github.xingjianluo.creditriskscoringenginejava.domain.CaseAction;
import com.github.xingjianluo.creditriskscoringenginejava.domain.CaseStatus;
import com.github.xingjianluo.creditriskscoringenginejava.domain.ReviewCase;
import com.github.xingjianluo.creditriskscoringenginejava.domain.ReviewError;
import com.github.xingjianluo.creditriskscoringenginejava.domain.Role;
import com.github.xingjianluo.creditriskscoringenginejava.domain.ScoreResult;
import com.github.xingjianluo.creditriskscoringenginejava.service.CaseService;
import com.github.xingjianluo.creditriskscoringenginejava.web.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 单测（Spring 上下文）：幂等开案、状态机非法迁移、角色权限与原因必填，
 * 每类拒绝都必须有可区分的错误码。
 */
@SpringBootTest
class CaseStateMachineTest {

    private static final Logger log = LoggerFactory.getLogger(CaseStateMachineTest.class);

    @Autowired
    private CaseService caseService;
    @Autowired
    private com.github.xingjianluo.creditriskscoringenginejava.service.ScoringService scoringService;

    @BeforeEach
    void reset() {
        scoringService.resetAll();
    }

    private ScoreResult highScore(String txnId) {
        return new ScoreResult(txnId, "ACC-CASE", 80, com.github.xingjianluo.creditriskscoringenginejava.domain.RiskLevel.HIGH,
                List.of(), "v1", Instant.parse("2026-09-20T10:00:00Z"));
    }

    @Test
    void openCase_isIdempotentPerTransaction() {
        ScoreResult s = highScore("case-txn-1");
        ReviewCase first = caseService.openFor(s);
        ReviewCase again = caseService.openFor(s);
        log.info("[测试输入] 对同一可疑流水两次开案 -> caseId1={} caseId2={}",
                first.getCaseId(), again.getCaseId());
        assertEquals(first.getCaseId(), again.getCaseId());
        assertEquals(1, caseService.list().size());
        assertEquals(CaseStatus.OPEN, first.getStatus());
        // 案件保留评分快照与规则版本，便于追溯
        assertEquals(80, first.getScoreSnapshot());
        assertEquals("v1", first.getRulesVersion());
    }

    @Test
    void legalHappyPath_claimEscalateCloseWithAuditTrail() {
        ReviewCase c = caseService.openFor(highScore("case-txn-2"));
        caseService.transition(c.getCaseId(), CaseAction.CLAIM, Role.ANALYST, "alice", null);
        caseService.transition(c.getCaseId(), CaseAction.ESCALATE, Role.ANALYST, "alice", null);
        // ESCALATED -> CLOSE 仅 SENIOR，且必须填写原因
        ReviewExceptionProbe.expectError(ReviewError.FORBIDDEN_ROLE,
                () -> caseService.transition(c.getCaseId(), CaseAction.CLOSE,
                        Role.ANALYST, "alice", "done"));
        ReviewExceptionProbe.expectError(ReviewError.REASON_REQUIRED,
                () -> caseService.transition(c.getCaseId(), CaseAction.CLOSE,
                        Role.SENIOR, "bob", "  "));
        ReviewCase closed = caseService.transition(c.getCaseId(), CaseAction.CLOSE,
                Role.SENIOR, "bob", "confirmed fraud");

        log.info("[判定依据] 最终状态={} 流转记录数={}", closed.getStatus(),
                closed.getHistory().size());
        assertEquals(CaseStatus.CLOSED, closed.getStatus());
        assertEquals("alice", closed.getAssignee());
        assertEquals("confirmed fraud", closed.getCloseReason());
        assertEquals(3, closed.getHistory().size());
    }

    @Test
    void illegalTransitionsAndDismissPermissions_areRejectedWithDistinctCodes() {
        ReviewCase c = caseService.openFor(highScore("case-txn-3"));

        // OPEN 状态不允许 ESCALATE / CLAIM 需要先存在案件
        ReviewExceptionProbe.expectError(ReviewError.ILLEGAL_TRANSITION,
                () -> caseService.transition(c.getCaseId(), CaseAction.ESCALATE,
                        Role.ANALYST, "a", null));
        // OPEN 直接 DISMISS 仅 SENIOR
        ReviewExceptionProbe.expectError(ReviewError.FORBIDDEN_ROLE,
                () -> caseService.transition(c.getCaseId(), CaseAction.DISMISS,
                        Role.ANALYST, "a", "x"));
        ReviewExceptionProbe.expectError(ReviewError.REASON_REQUIRED,
                () -> caseService.transition(c.getCaseId(), CaseAction.DISMISS,
                        Role.SENIOR, "b", null));
        caseService.transition(c.getCaseId(), CaseAction.DISMISS, Role.SENIOR,
                "b", "false alarm");

        // 终态不允许任何迁移
        ReviewExceptionProbe.expectError(ReviewError.ILLEGAL_TRANSITION,
                () -> caseService.transition(c.getCaseId(), CaseAction.CLAIM,
                        Role.ANALYST, "a", null));

        // 不存在的案件
        ReviewExceptionProbe.expectError(ReviewError.CASE_NOT_FOUND,
                () -> caseService.get("CASE-999999"));
        log.info("[判定依据] 非法迁移/权限/原因/不存在 四类错误码均可区分");
    }

    /** 简易断言工具：断言抛出指定 ReviewError 的 ApiException。 */
    static final class ReviewExceptionProbe {
        static void expectError(ReviewError expected, Runnable action) {
            ApiException ex = assertThrows(ApiException.class, action::run);
            Logger log = LoggerFactory.getLogger(ReviewExceptionProbe.class);
            log.info("[判定依据] 预期拒绝 code={} 实际 code={}",
                    expected, ex.getError());
            assertEquals(expected, ex.getError());
        }
    }
}
