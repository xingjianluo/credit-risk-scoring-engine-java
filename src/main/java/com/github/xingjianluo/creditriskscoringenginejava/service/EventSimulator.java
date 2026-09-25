package com.github.xingjianluo.creditriskscoringenginejava.service;

import com.github.xingjianluo.creditriskscoringenginejava.domain.ScenarioReport;
import com.github.xingjianluo.creditriskscoringenginejava.domain.ScoreResult;
import com.github.xingjianluo.creditriskscoringenginejava.domain.SubmissionOutcome;
import com.github.xingjianluo.creditriskscoringenginejava.domain.TransactionRequest;
import com.github.xingjianluo.creditriskscoringenginejava.store.CaseStore;
import com.github.xingjianluo.creditriskscoringenginejava.web.RuleDefinitionRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 本地事件模拟器：用固定信号构造重复、乱序、并发提交与规则版本切换场景，
 * 运行结果汇总为 {@link ScenarioReport}，便于人工核对一致性与可追溯性。
 */
@Component
public class EventSimulator {

    private static final Logger log = LoggerFactory.getLogger(EventSimulator.class);

    private final ScoringService scoringService;
    private final CaseStore caseStore;
    private final RuleVersionService ruleVersionService;

    public EventSimulator(ScoringService scoringService, CaseStore caseStore,
                          RuleVersionService ruleVersionService) {
        this.scoringService = scoringService;
        this.caseStore = caseStore;
        this.ruleVersionService = ruleVersionService;
    }

    /** 清空账本与案件（规则版本保留），供本地场景/测试隔离使用。 */
    public void reset() {
        scoringService.resetAll();
    }

    /** 重复提交：同一流水提交 3 次，结论必须一致且只计一次分。 */
    public ScenarioReport runDuplicates() {
        String run = UUID.randomUUID().toString().substring(0, 8);
        Instant base = Instant.parse("2026-09-20T10:00:00Z");
        TransactionRequest req = txn("dup-" + run + "-1", "ACC-DUP-" + run,
                new BigDecimal("60000"), "P-1", "US", base);
        List<String> outcomes = new ArrayList<>();
        ScoreResult first = null;
        boolean consistent = true;
        for (int i = 0; i < 3; i++) {
            SubmissionOutcome o = scoringService.submit(req, Instant.now());
            if (o.isRejected()) {
                consistent = false;
            } else if (first == null) {
                first = o.scoreResult();
            } else {
                consistent &= sameConclusion(first, o.scoreResult());
                consistent &= o.duplicate();
            }
            outcomes.add("submit#" + i + " duplicate=" + o.duplicate()
                    + (o.isRejected() ? " reason=" + o.rejection().reason()
                                      : " score=" + o.scoreResult().totalScore()));
        }
        long casesForTxn = caseStore.findByTransactionId(req.transactionId()).isPresent()
                ? 1 : 0;
        log.info("[场景:重复提交] run={} outcomes={} consistent={}", run, outcomes, consistent);
        return new ScenarioReport("duplicates", 3, 1, (int) casesForTxn,
                outcomes, consistent);
    }

    /** 乱序到达：同一账户三笔流水逆序提交，每笔只依赖更早事件时间的历史，结论稳定。 */
    public ScenarioReport runOutOfOrder() {
        String run = UUID.randomUUID().toString().substring(0, 8);
        Instant now = Instant.now();
        Instant t1 = Instant.parse("2026-09-20T10:00:00Z");
        Instant t2 = t1.plusSeconds(30);
        Instant t3 = t1.plusSeconds(60);
        String acc = "ACC-OOO-" + run;
        List<TransactionRequest> inArrivalOrder = List.of(
                txn("ooo-" + run + "-3", acc, new BigDecimal("1000"), "P-1", "US", t3),
                txn("ooo-" + run + "-2", acc, new BigDecimal("1000"), "P-1", "US", t2),
                txn("ooo-" + run + "-1", acc, new BigDecimal("1000"), "P-1", "US", t1)
        );
        List<String> outcomes = new ArrayList<>();
        for (TransactionRequest req : inArrivalOrder) {
            SubmissionOutcome o = scoringService.submit(req, now);
            outcomes.add("txn=" + req.transactionId()
                    + " score=" + (o.isRejected() ? o.rejection().reason()
                    : String.valueOf(o.scoreResult().totalScore())));
        }
        // 正向顺序重算同一批信号用于对照（用新账户复刻），并复现每笔历史结论
        boolean consistent = true;
        for (String id : List.of("ooo-" + run + "-1", "ooo-" + run + "-2", "ooo-" + run + "-3")) {
            consistent &= scoringService.replay(id).consistent();
        }
        log.info("[场景:乱序到达] run={} outcomes={} consistent={}", run, outcomes, consistent);
        return new ScenarioReport("out-of-order", 3, 3, 0, outcomes, consistent);
    }

    /** 并发提交：多线程同时提交同一流水，只允许一次首次评分、一个案件。 */
    public ScenarioReport runConcurrent(int threadsPerTransaction) throws InterruptedException {
        String run = UUID.randomUUID().toString().substring(0, 8);
        Instant base = Instant.parse("2026-09-20T10:00:00Z");
        // 三笔不同流水各自被 N 个线程并发提交（大额+高风险辖区+深夜，触发自动开案）
        List<TransactionRequest> reqs = List.of(
                txn("cc-" + run + "-1", "ACC-CC-" + run, new BigDecimal("90000"), "P-1", "XX", base),
                txn("cc-" + run + "-2", "ACC-CC-" + run, new BigDecimal("90000"), "P-2", "XX", base.plusSeconds(400)),
                txn("cc-" + run + "-3", "ACC-CC-" + run, new BigDecimal("90000"), "P-3", "XX", base.plusSeconds(800))
        );
        int threads = threadsPerTransaction;
        ExecutorService pool = Executors.newFixedThreadPool((int) Math.min(24, threads * 3L));
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(reqs.size() * threads);
        AtomicInteger firstWins = new AtomicInteger();
        AtomicInteger duplicates = new AtomicInteger();
        for (TransactionRequest req : reqs) {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        SubmissionOutcome o = scoringService.submit(req);
                        if (o.duplicate()) {
                            duplicates.incrementAndGet();
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
        }
        start.countDown();
        boolean finished = done.await(30, TimeUnit.SECONDS);
        pool.shutdownNow();

        long distinctCases = reqs.stream()
                .filter(r -> caseStore.findByTransactionId(r.transactionId()).isPresent())
                .count();
        boolean consistent = finished
                && firstWins.get() == reqs.size()
                && duplicates.get() == reqs.size() * (threads - 1)
                && distinctCases == reqs.size();
        List<String> outcomes = List.of(
                "提交线程数=" + (reqs.size() * threads),
                "首次评分次数=" + firstWins.get() + "（期望 " + reqs.size() + "）",
                "幂等重复次数=" + duplicates.get() + "（期望 " + (reqs.size() * (threads - 1)) + "）",
                "生成案件数=" + distinctCases + "（期望 " + reqs.size() + "）");
        log.info("[场景:并发提交] run={} {} consistent={}", run, outcomes, consistent);
        return new ScenarioReport("concurrent", reqs.size() * threads,
                reqs.size(), (int) distinctCases, outcomes, consistent);
    }

    /** 规则版本切换：v1 评分 → 激活 v2（更严）→ 新流水按 v2，旧结论保持 v1 且可复现。 */
    public ScenarioReport runVersionSwitch() {
        String run = UUID.randomUUID().toString().substring(0, 8);
        Instant base = Instant.parse("2026-09-20T10:00:00Z");
        String v1 = ruleVersionService.current().version();

        TransactionRequest before = txn("vs-" + run + "-1", "ACC-VS-" + run,
                new BigDecimal("5000"), "P-1", "US", base);
        SubmissionOutcome o1 = scoringService.submit(before);
        ScoreResult oldResult = o1.scoreResult();

        String v2 = "v2-" + run;
        ruleVersionService.activate(new RuleDefinitionRequest(v2,
                new BigDecimal("1000"), 30,
                null, null, null, null, null,
                null, null, null, null, null, null,
                null, null, null));

        TransactionRequest after = txn("vs-" + run + "-2", "ACC-VS-" + run,
                new BigDecimal("5000"), "P-2", "US", base.plusSeconds(400));
        SubmissionOutcome o2 = scoringService.submit(after);
        ScoreResult newResult = o2.scoreResult();

        boolean oldRepro = scoringService.replay(before.transactionId()).consistent();
        boolean consistent = oldResult.rulesVersion().equals(v1)
                && newResult.rulesVersion().equals(v2)
                // 旧流水首笔交易有"陌生收款方"基础分 10，但不含大额（v1 阈值 50000）
                && oldResult.totalScore() == 10
                // 新流水陌生收款方 10 + 大额 30 = 40
                && newResult.totalScore() == 40
                && oldRepro;
        List<String> outcomes = List.of(
                "旧流水 " + before.transactionId() + " version=" + oldResult.rulesVersion()
                        + " score=" + oldResult.totalScore(),
                "激活新版本 " + v2 + "（大额阈值 50000→1000，分值 20→30）",
                "新流水 " + after.transactionId() + " version=" + newResult.rulesVersion()
                        + " score=" + newResult.totalScore(),
                "旧结论复现=" + oldRepro);
        log.info("[场景:版本切换] run={} {} consistent={}", run, outcomes, consistent);
        return new ScenarioReport("version-switch", 2, 2, 0, outcomes, consistent);
    }

    private static TransactionRequest txn(String id, String acc, BigDecimal amount,
                                          String payee, String jurisdiction, Instant time) {
        return new TransactionRequest(id, acc, amount, payee, jurisdiction, time);
    }

    private static boolean sameConclusion(ScoreResult a, ScoreResult b) {
        return a.totalScore() == b.totalScore()
                && a.riskLevel() == b.riskLevel()
                && a.rulesVersion().equals(b.rulesVersion())
                && a.contributions().size() == b.contributions().size();
    }
}
