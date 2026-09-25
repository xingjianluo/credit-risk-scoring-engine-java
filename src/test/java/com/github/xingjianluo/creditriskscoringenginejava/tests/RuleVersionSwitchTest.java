package com.github.xingjianluo.creditriskscoringenginejava.tests;

import com.github.xingjianluo.creditriskscoringenginejava.domain.RuleSet;
import com.github.xingjianluo.creditriskscoringenginejava.domain.ScoreResult;
import com.github.xingjianluo.creditriskscoringenginejava.domain.SubmissionOutcome;
import com.github.xingjianluo.creditriskscoringenginejava.domain.TransactionRequest;
import com.github.xingjianluo.creditriskscoringenginejava.service.RuleVersionService;
import com.github.xingjianluo.creditriskscoringenginejava.service.ScoringService;
import com.github.xingjianluo.creditriskscoringenginejava.web.ApiException;
import com.github.xingjianluo.creditriskscoringenginejava.web.RuleDefinitionRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 规则版本化测试：新版本只影响新流水，历史结论冻结且可复现，旧版本仍可读取。
 */
@SpringBootTest
class RuleVersionSwitchTest {

    private static final Logger log = LoggerFactory.getLogger(RuleVersionSwitchTest.class);

    @Autowired
    private ScoringService scoringService;
    @Autowired
    private RuleVersionService ruleVersionService;

    @BeforeEach
    void reset() {
        scoringService.resetAll();
    }

    private TransactionRequest txn(String id, String amount, Instant t) {
        return new TransactionRequest(id, "ACC-RV", new BigDecimal(amount),
                "P1", "US", t);
    }

    @Test
    void fullVersionSwitchFlow() {
        Instant t1 = Instant.parse("2026-09-20T10:00:00Z");
        SubmissionOutcome before = scoringService.submit(txn("rv-old-2", "5000", t1));
        ScoreResult oldResult = before.scoreResult();

        RuleSet v2 = ruleVersionService.activate(new RuleDefinitionRequest(
                "v2-test", new BigDecimal("1000"), 30,
                null, null, null, java.util.List.of(), null,
                null, null, null, null, null, null,
                null, null, null));
        log.info("[测试输入] 激活 {} 大额阈值 50000->1000 分值 20->30，高风险辖区清空",
                v2.version());
        assertEquals("v2-test", ruleVersionService.current().version());

        SubmissionOutcome after = scoringService.submit(
                txn("rv-new-2", "5000", t1.plusSeconds(400)));
        ScoreResult newResult = after.scoreResult();
        log.info("[判定依据] 新流水 version={} score={}（旧流水 version={} score={}）",
                newResult.rulesVersion(), newResult.totalScore(),
                oldResult.rulesVersion(), oldResult.totalScore());

        // 旧版本仍可读取
        RuleSet fetched = ruleVersionService.get("v1");
        assertNotNull(fetched);
        assertEquals(new BigDecimal("50000.00"), fetched.amountLargeThreshold());

        // 历史结论不随新版本改变，且可按存档版本复现
        ScoreResult storedOld = scoringService.score("rv-old-2");
        assertEquals("v1", storedOld.rulesVersion());
        assertEquals(oldResult.totalScore(), storedOld.totalScore());
        assertTrue(scoringService.replay("rv-old-2").consistent());
        assertTrue(scoringService.replay("rv-new-2").consistent());

        // 新流水按 v2：5000 >= 1000 大额 30 分
        assertEquals("v2-test", newResult.rulesVersion());
        assertTrue(newResult.totalScore() >= 30);

        // 版本号不可覆盖
        assertThrows(ApiException.class, () ->
                ruleVersionService.activate(new RuleDefinitionRequest(
                        "v2-test", new BigDecimal("2000"), 10,
                        null, null, null, null, null,
                        null, null, null, null, null, null,
                        null, null, null)));
        // 非法定义（负数阈值）必须被拒绝
        assertThrows(ApiException.class, () ->
                ruleVersionService.activate(new RuleDefinitionRequest(
                        "v3-bad", new BigDecimal("-1"), 10,
                        null, null, null, null, null,
                        null, null, null, null, null, null,
                        null, null, null)));
    }
}
