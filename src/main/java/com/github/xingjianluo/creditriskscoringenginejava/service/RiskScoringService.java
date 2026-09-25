package com.github.xingjianluo.creditriskscoringenginejava.service;

import com.github.xingjianluo.creditriskscoringenginejava.ledger.TransactionLedger;
import com.github.xingjianluo.creditriskscoringenginejava.model.RejectionRecord;
import com.github.xingjianluo.creditriskscoringenginejava.model.RiskScoreResult;
import com.github.xingjianluo.creditriskscoringenginejava.model.SubmissionOutcome;
import com.github.xingjianluo.creditriskscoringenginejava.model.Transaction;
import com.github.xingjianluo.creditriskscoringenginejava.validation.TransactionValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 风险评分服务（对外门面）。
 *
 * <p>一致性模型：<b>先收集去重、再按事件时间（eventTime）排序，最后逐笔评分</b>。
 * 评分前同批全部合法流水已入账，因此每笔流水看到的历史基线只取决于事件时间，
 * 与流水到达顺序无关；重复提交在收集阶段折叠为一条，不会重复计分或重复开案。
 *
 * <p>已存在结论的交易直接返回首次结果（duplicate=true），保证幂等。
 */
@Service
public class RiskScoringService {

    private static final Logger log = LoggerFactory.getLogger(RiskScoringService.class);

    private final TransactionLedger ledger;
    private final TransactionValidator validator;
    private final ScoringPipeline pipeline;

    /** 每个 transactionId 一把常驻锁：并发的单条提交按交易串行，首个评分、其余幂等返回。 */
    private final ConcurrentMap<String, Object> txLocks = new ConcurrentHashMap<>();

    public RiskScoringService(TransactionLedger ledger, TransactionValidator validator,
                              ScoringPipeline pipeline) {
        this.ledger = ledger;
        this.validator = validator;
        this.pipeline = pipeline;
    }

    /** 提交单条流水；重复提交返回首次结论且不重复计分。并发同 ID 提交按交易串行。 */
    public SubmissionOutcome submit(Transaction tx) {
        String id = tx == null ? null : tx.transactionId();
        if (id == null || id.isBlank()) {
            return submitBatch(List.of(tx)).get(0);
        }
        synchronized (txLocks.computeIfAbsent(id, k -> new Object())) {
            return submitBatch(List.of(tx)).get(0);
        }
    }

    /**
     * 批量提交：乱序/并发/顺序三种到达方式统一走此入口，结果与到达排列无关。
     * 返回与输入等长、同序的结果列表；重复输入得到 duplicate=true 的同一结论。
     */
    public List<SubmissionOutcome> submitBatch(List<Transaction> inputs) {
        // 1) 已存在结论的交易直接复用首次结果
        List<SubmissionOutcome> results = new ArrayList<>(inputs.size());
        List<Transaction> toProcess = new ArrayList<>();
        for (Transaction tx : inputs) {
            String id = tx == null ? null : tx.transactionId();
            if (id != null) {
                Optional<RiskScoreResult> existing = ledger.scoreOf(id);
                if (existing.isPresent()) {
                    log.info("[SCORE-DUP] tx={} 返回首次评分结果，不重复计分", id);
                    results.add(SubmissionOutcome.scored(existing.get(), true,
                            pipeline.caseIdsOf(id)));
                    continue;
                }
                Optional<RejectionRecord> existingRejection = ledger.rejectionOf(id);
                if (existingRejection.isPresent()) {
                    log.info("[SCORE-DUP] tx={} 返回首次拒绝记录，原因={}", id,
                            existingRejection.get().reason());
                    results.add(SubmissionOutcome.rejected(existingRejection.get(), true));
                    continue;
                }
            }
            results.add(null);
            toProcess.add(tx);
        }

        // 2) 收集去重（保留首次出现的对象/顺序），空 ID 逐条拒绝
        Map<String, Transaction> unique = new LinkedHashMap<>();
        List<Transaction> nullIdTx = new ArrayList<>();
        for (Transaction tx : toProcess) {
            if (tx == null || tx.transactionId() == null || tx.transactionId().isBlank()) {
                nullIdTx.add(tx);
            } else {
                unique.putIfAbsent(tx.transactionId(), tx);
            }
        }

        // 3) 新收集到的合法候选先全部入账（去重），使基线包含同批所有更早的流水
        List<Transaction> candidates = new ArrayList<>(unique.values());
        for (Transaction tx : candidates) {
            ledger.putIfAbsentTransaction(tx);
        }

        // 4) 按事件时间排序评分；非法流水在管线内被拒绝并移出账本
        List<Transaction> ordered = new ArrayList<>(candidates);
        ordered.sort(Comparator.comparing(t -> t.eventTime() == null ? Instant.MAX : t.eventTime()));
        Map<String, SubmissionOutcome> fresh = new LinkedHashMap<>();
        for (Transaction tx : ordered) {
            fresh.put(tx.transactionId(), pipeline.evaluate(tx, true));
        }
        for (Transaction tx : nullIdTx) {
            RejectionRecord rejection = validator.validate(tx);
            if (rejection != null) {
                ledger.saveRejection(rejection);
                log.warn("[REJECT] tx=null reason={} detail={}", rejection.reason(), rejection.detail());
            }
        }

        // 5) 按输入顺序回填：新交易取本次结论，重复输入标记 duplicate=true
        Map<String, Integer> seenInBatch = new LinkedHashMap<>();
        int freshCount = 0;
        for (int i = 0; i < inputs.size(); i++) {
            if (results.get(i) != null) {
                continue;
            }
            Transaction tx = inputs.get(i);
            if (tx == null || tx.transactionId() == null || tx.transactionId().isBlank()) {
                RejectionRecord r = validator.validate(tx);
                results.set(i, SubmissionOutcome.rejected(r, false));
                continue;
            }
            String id = tx.transactionId();
            int occurrence = seenInBatch.merge(id, 1, Integer::sum);
            SubmissionOutcome base = fresh.get(id);
            if (occurrence > 1) {
                results.set(i, asDuplicate(base));
            } else {
                results.set(i, base);
                freshCount++;
            }
        }
        log.info("[BATCH] 输入={} 去重后新交易={} 评分/拒绝完成", inputs.size(), freshCount);
        return results;
    }

    private SubmissionOutcome asDuplicate(SubmissionOutcome base) {
        if (base == null) {
            throw new IllegalStateException("重复提交缺少首次结论");
        }
        if (base.accepted()) {
            return SubmissionOutcome.scored(base.score(), true, base.caseIds());
        }
        return SubmissionOutcome.rejected(base.rejection(), true);
    }

    public Optional<RiskScoreResult> getScore(String transactionId) {
        return ledger.scoreOf(transactionId);
    }

    public List<RiskScoreResult> allScores() {
        return ledger.allScores();
    }
}
