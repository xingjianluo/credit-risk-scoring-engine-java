package com.github.xingjianluo.creditriskscoringenginejava.service;

import com.github.xingjianluo.creditriskscoringenginejava.caseflow.ReviewCaseService;
import com.github.xingjianluo.creditriskscoringenginejava.ledger.TransactionLedger;
import com.github.xingjianluo.creditriskscoringenginejava.model.ArrivalMode;
import com.github.xingjianluo.creditriskscoringenginejava.model.BatchReport;
import com.github.xingjianluo.creditriskscoringenginejava.model.SubmissionOutcome;
import com.github.xingjianluo.creditriskscoringenginejava.model.Transaction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;

/**
 * 本地事件模拟器：用内存事件流模拟顺序、乱序、并发三种到达方式。
 *
 * <p>每次运行前清空内存账本与案件库。三种方式都把“已到达”的同一批事件交给
 * {@link RiskScoringService#submitBatch}：服务内部先收集去重、再按事件时间评分，
 * 因此无论到达排列如何，接受/拒绝/重复/开案计数与逐笔结论完全一致。
 * 并发模式额外用 {@link CyclicBarrier} 让所有线程同时释放后再收集，
 * 以真实的并发提交压力验证计数与案件状态最终一致。
 */
@Component
public class LocalEventSimulator {

    private static final Logger log = LoggerFactory.getLogger(LocalEventSimulator.class);

    private final RiskScoringService scoringService;
    private final TransactionLedger ledger;
    private final ReviewCaseService caseService;

    public LocalEventSimulator(RiskScoringService scoringService, TransactionLedger ledger,
                               ReviewCaseService caseService) {
        this.scoringService = scoringService;
        this.ledger = ledger;
        this.caseService = caseService;
    }

    public BatchReport run(String runId, List<Transaction> events, ArrivalMode mode) {
        ledger.clear();
        caseService.clear();
        String id = runId == null ? UUID.randomUUID().toString() : runId;
        log.info("[SIM-START] runId={} mode={} inputs={}", id, mode, events.size());

        List<Transaction> arrived = collectByMode(events, mode);
        List<SubmissionOutcome> outcomes = scoringService.submitBatch(arrived);

        BatchReport report = aggregate(id, mode, events, outcomes);
        log.info("[SIM-END] runId={} mode={} 唯一={} 接受={} 拒绝={} 重复={} 开案={}",
                id, mode, report.uniqueTransactions(), report.accepted(),
                report.rejected(), report.duplicates(), report.casesOpened());
        return report;
    }

    /**
     * 按到达方式收集事件：
     * IN_ORDER 按事件时间；OUT_OF_ORDER 确定性洗牌+逆序；
     * CONCURRENT 多线程经 CyclicBarrier 同时进入收集队列（顺序不确定）。
     */
    private List<Transaction> collectByMode(List<Transaction> events, ArrivalMode mode) {
        List<Transaction> ordered = new ArrayList<>(events);
        ordered.sort(Comparator.comparing(t -> t == null || t.eventTime() == null
                ? Instant.MAX : t.eventTime()));
        return switch (mode) {
            case IN_ORDER -> ordered;
            case OUT_OF_ORDER -> {
                List<Transaction> shuffled = new ArrayList<>(ordered);
                Collections.shuffle(shuffled, new Random(42));
                Collections.reverse(shuffled);
                yield shuffled;
            }
            case CONCURRENT -> {
                ConcurrentLinkedQueue<Transaction> queue = new ConcurrentLinkedQueue<>();
                CyclicBarrier barrier = new CyclicBarrier(Math.max(events.size(), 1));
                List<CompletableFuture<Void>> futures = events.stream()
                        .map(tx -> CompletableFuture.runAsync(() -> {
                            await(barrier);
                            queue.add(tx);
                        }))
                        .toList();
                CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
                yield new ArrayList<>(queue);
            }
        };
    }

    private static void await(CyclicBarrier barrier) {
        try {
            barrier.await();
        } catch (Exception e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private BatchReport aggregate(String runId, ArrivalMode mode,
                                  List<Transaction> inputs, List<SubmissionOutcome> outcomes) {
        Set<String> uniqueIds = new HashSet<>();
        for (Transaction tx : inputs) {
            if (tx != null && tx.transactionId() != null) {
                uniqueIds.add(tx.transactionId());
            }
        }
        int accepted = (int) outcomes.stream().filter(SubmissionOutcome::accepted).count();
        int rejected = (int) outcomes.stream().filter(o -> !o.accepted()).count();
        int duplicates = (int) outcomes.stream().filter(SubmissionOutcome::duplicate).count();
        int casesOpened = caseService.all().size();
        return new BatchReport(runId, mode, inputs.size(), uniqueIds.size(),
                accepted, rejected, duplicates, casesOpened, List.copyOf(outcomes));
    }
}
