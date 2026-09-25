package com.github.xingjianluo.creditriskscoringenginejava.tests;

import com.github.xingjianluo.creditriskscoringenginejava.domain.RejectReason;
import com.github.xingjianluo.creditriskscoringenginejava.domain.ReplayReport;
import com.github.xingjianluo.creditriskscoringenginejava.domain.ScoreResult;
import com.github.xingjianluo.creditriskscoringenginejava.domain.SubmissionOutcome;
import com.github.xingjianluo.creditriskscoringenginejava.domain.TransactionRequest;
import com.github.xingjianluo.creditriskscoringenginejava.service.ScoringService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 集成测试：重复提交、乱序到达、拒绝落账与历史复现的一致性。
 */
@SpringBootTest
class ScoringFlowIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(ScoringFlowIntegrationTest.class);

    @Autowired
    private ScoringService scoringService;

    @BeforeEach
    void reset() {
        scoringService.resetAll();
    }

    private TransactionRequest txn(String id, String account, String amount,
                                   String payee, String jurisdiction, Instant t) {
        return new TransactionRequest(id, account, new BigDecimal(amount), payee,
                jurisdiction, t);
    }

    @Test
    void duplicateSubmissions_produceOneStableConclusion() {
        Instant t = Instant.parse("2026-09-20T10:00:00Z");
        TransactionRequest req = txn("dup-1", "ACC-D", "80000", "P1", "XX", t);

        List<SubmissionOutcome> outcomes = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            outcomes.add(scoringService.submit(req));
        }
        outcomes.forEach(o -> log.info("[测试输入] 重复提交 dup-1 第{}次 -> duplicate={} score={}",
                outcomes.indexOf(o) + 1, o.duplicate(),
                o.isRejected() ? o.rejection().reason() : o.scoreResult().totalScore()));

        assertEquals(1, scoringService.scores().size(), "只允许计一次分");
        assertFalse(outcomes.get(0).duplicate());
        for (int i = 1; i < 5; i++) {
            assertTrue(outcomes.get(i).duplicate(), "后续提交必须标记 duplicate");
            ScoreResult a = outcomes.get(0).scoreResult();
            ScoreResult b = outcomes.get(i).scoreResult();
            assertEquals(a.totalScore(), b.totalScore());
            assertEquals(a.riskLevel(), b.riskLevel());
            assertEquals(a.rulesVersion(), b.rulesVersion());
            assertEquals(a.contributions().size(), b.contributions().size());
        }
    }

    @Test
    void outOfOrderArrival_conclusionsAreOrderIndependentAndReproducible() {
        Instant t1 = Instant.parse("2026-09-20T10:00:00Z");
        Instant t2 = t1.plusSeconds(30);
        Instant t3 = t1.plusSeconds(60);
        String acc = "ACC-OOO";

        // 逆序到达：t3 -> t2 -> t1
        scoringService.submit(txn("ooo-3", acc, "1000", "P1", "US", t3));
        scoringService.submit(txn("ooo-2", acc, "1000", "P1", "US", t2));
        SubmissionOutcome first = scoringService.submit(
                txn("ooo-1", acc, "1000", "P1", "US", t1));

        ScoreResult r1 = scoringService.score("ooo-1");
        ScoreResult r2 = scoringService.score("ooo-2");
        ScoreResult r3 = scoringService.score("ooo-3");
        log.info("[测试输入] 逆序到达 t3,t2,t1 -> [判定依据] 分数 {} {} {}",
                r1.totalScore(), r2.totalScore(), r3.totalScore());

        // t1 是最后到的：它得出结论后不得再被改变
        assertEquals(first.scoreResult().totalScore(), r1.totalScore());
        // t2 在 t1 到达前已评分，其存档结论与基线快照必须保持当时的样子（无更早基线）
        assertTrue(r2.baselineTransactionIds().isEmpty(),
                "乱序下已存档的基线快照不可被后到流水改写");
        log.info("[判定依据] t2 存档基线快照={}（t1 当时尚未到达）",
                r2.baselineTransactionIds());

        for (String id : List.of("ooo-1", "ooo-2", "ooo-3")) {
            ReplayReport report = scoringService.replay(id);
            log.info("[判定依据] 复现 {} consistent={} note={}",
                    id, report.consistent(), report.note());
            assertTrue(report.consistent());
        }
    }

    @Test
    void invalidTransactions_areRejectedWithDistinctReasonsNotSilentlyDropped() {
        scoringService.submit(txn("bad-1", "ACC-X", "-1", "P", "US",
                Instant.parse("2026-09-20T10:00:00Z")));
        scoringService.submit(new TransactionRequest("bad-2", "ACC-X", null, "P", "US",
                Instant.parse("2026-09-20T10:00:00Z")));

        assertEquals(0, scoringService.scores().size());
        assertEquals(2, scoringService.rejections().size());
        var reasons = scoringService.rejections().stream()
                .map(r -> r.reason()).distinct().toList();
        log.info("[测试输入] 两笔非法流水 -> [判定依据] 拒绝原因集合={}", reasons);
        assertTrue(reasons.contains(RejectReason.NON_POSITIVE_AMOUNT));
        assertTrue(reasons.contains(RejectReason.MISSING_AMOUNT));

        // 同一非法流水重复提交，拒绝原因稳定
        var again = scoringService.submit(txn("bad-1", "ACC-X", "-1", "P", "US",
                Instant.parse("2026-09-20T10:00:00Z")));
        assertTrue(again.isRejected());
        assertEquals(RejectReason.NON_POSITIVE_AMOUNT, again.rejection().reason());
        assertTrue(again.duplicate());
    }
}
