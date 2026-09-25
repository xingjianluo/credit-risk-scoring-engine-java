package com.github.xingjianluo.creditriskscoringenginejava.tests;

import com.github.xingjianluo.creditriskscoringenginejava.domain.ScoreResult;
import com.github.xingjianluo.creditriskscoringenginejava.domain.SubmissionOutcome;
import com.github.xingjianluo.creditriskscoringenginejava.domain.TransactionRequest;
import com.github.xingjianluo.creditriskscoringenginejava.service.CaseService;
import com.github.xingjianluo.creditriskscoringenginejava.service.ScoringService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 并发测试：多线程并发提交相同流水时，首次评分、案件创建与计数必须最终一致。
 */
@SpringBootTest
class ConcurrentScoringTest {

    private static final Logger log = LoggerFactory.getLogger(ConcurrentScoringTest.class);

    @Autowired
    private ScoringService scoringService;
    @Autowired
    private CaseService caseService;

    @BeforeEach
    void reset() {
        scoringService.resetAll();
    }

    @RepeatedTest(5)
    void concurrentDuplicateSubmissions_scoreAndCaseExactlyOnce() throws Exception {
        int threads = 16;
        Instant t = Instant.parse("2026-09-20T03:00:00Z"); // 深夜
        TransactionRequest req = new TransactionRequest("cc-txn-1", "ACC-CC",
                new BigDecimal("90000"), "P1", "XX", t);

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger firstWins = new AtomicInteger();
        AtomicInteger duplicateWins = new AtomicInteger();
        ScoreResult[] results = new ScoreResult[threads];

        for (int i = 0; i < threads; i++) {
            int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    SubmissionOutcome o = scoringService.submit(req);
                    results[idx] = o.scoreResult();
                    if (o.duplicate()) {
                        duplicateWins.incrementAndGet();
                    } else {
                        firstWins.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS));
        pool.shutdownNow();

        log.info("[测试输入] {} 线程并发提交同一可疑流水 -> 首次={} 幂等={} 案件数={}",
                threads, firstWins.get(), duplicateWins.get(), caseService.list().size());

        assertEquals(1, firstWins.get(), "恰好一次首次评分");
        assertEquals(threads - 1, duplicateWins.get(), "其余全部幂等返回");
        assertEquals(1, scoringService.scores().size());
        assertEquals(1, caseService.list().size(), "恰好一个审查案件");

        ScoreResult expected = results[0];
        for (ScoreResult r : results) {
            assertNotNull(r);
            assertEquals(expected.totalScore(), r.totalScore());
            assertEquals(expected.rulesVersion(), r.rulesVersion());
        }
        // 高风险流水应自动开案
        assertEquals(1, caseService.findByTransactionId("cc-txn-1").stream().count());
    }

    @RepeatedTest(3)
    void concurrentMixedTransactions_countsStayConsistent() throws Exception {
        int distinct = 10;
        int copies = 8;
        Instant base = Instant.parse("2026-09-20T03:00:00Z");
        ExecutorService pool = Executors.newFixedThreadPool(20);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(distinct * copies);
        for (int d = 0; d < distinct; d++) {
            TransactionRequest req = new TransactionRequest("mix-" + d, "ACC-MIX",
                    new BigDecimal(90000 - d), "P" + d, "XX", base.plusSeconds(400L * d));
            for (int k = 0; k < copies; k++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        scoringService.submit(req);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
        }
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS));
        pool.shutdownNow();

        log.info("[测试输入] {} 笔不同流水各 {} 份并发副本 -> 评分数={} 案件数={}",
                distinct, copies, scoringService.scores().size(), caseService.list().size());
        assertEquals(distinct, scoringService.scores().size());
        assertEquals(distinct, caseService.list().size());
        // 各流水因子组合相同（陌生收款方+大额+高风险辖区+深夜），分数应全部稳定一致，
        // 且每条流水只有一条结论
        java.util.Set<String> ids = scoringService.scores().stream()
                .map(ScoreResult::transactionId).collect(java.util.stream.Collectors.toSet());
        assertEquals(distinct, ids.size());
        long distinctScores = scoringService.scores().stream()
                .map(ScoreResult::totalScore).distinct().count();
        assertEquals(1, distinctScores, "相同信号必须得到相同分数");
    }
}
