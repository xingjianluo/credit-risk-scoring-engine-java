package com.github.xingjianluo.creditriskscoringenginejava.service;

import com.github.xingjianluo.creditriskscoringenginejava.domain.RejectedSubmission;
import com.github.xingjianluo.creditriskscoringenginejava.domain.ReplayReport;
import com.github.xingjianluo.creditriskscoringenginejava.domain.ReviewError;
import com.github.xingjianluo.creditriskscoringenginejava.domain.RuleSet;
import com.github.xingjianluo.creditriskscoringenginejava.domain.ScoreResult;
import com.github.xingjianluo.creditriskscoringenginejava.domain.SubmissionOutcome;
import com.github.xingjianluo.creditriskscoringenginejava.domain.TransactionEvent;
import com.github.xingjianluo.creditriskscoringenginejava.domain.TransactionRequest;
import com.github.xingjianluo.creditriskscoringenginejava.domain.ValidationOutcome;
import com.github.xingjianluo.creditriskscoringenginejava.store.CaseStore;
import com.github.xingjianluo.creditriskscoringenginejava.store.Ledger;
import com.github.xingjianluo.creditriskscoringenginejava.store.RuleStore;
import com.github.xingjianluo.creditriskscoringenginejava.web.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * 评分总服务。
 *
 * <p>一致性保证：所有提交在同一把服务锁内完成"查重 → 校验 → 评分 → 入账 → 自动开案"。
 * <ul>
 *   <li>重复提交：按 transactionId 直接返回首次结论，并标记 duplicate，不二次计分；</li>
 *   <li>乱序到达：评分只读取 eventTime 更早的历史流水，先到流水结论不被后补的历史改变；</li>
 *   <li>并发提交：锁 + 账本 putIfAbsent 双保险，计数/开案最终一致且无重复。</li>
 * </ul>
 */
@Service
public class ScoringService {

    private static final Logger log = LoggerFactory.getLogger(ScoringService.class);

    private final Ledger ledger;
    private final CaseStore caseStore;
    private final RuleStore ruleStore;
    private final ScoringEngine engine;
    private final TransactionValidator validator;
    private final CaseService caseService;
    private final Object lock = new Object();

    public ScoringService(Ledger ledger, CaseStore caseStore, RuleStore ruleStore,
                          ScoringEngine engine, TransactionValidator validator,
                          CaseService caseService) {
        this.ledger = ledger;
        this.caseStore = caseStore;
        this.ruleStore = ruleStore;
        this.engine = engine;
        this.validator = validator;
        this.caseService = caseService;
    }

    public SubmissionOutcome submit(TransactionRequest request) {
        return submit(request, Instant.now());
    }

    public SubmissionOutcome submit(TransactionRequest request, Instant now) {
        synchronized (lock) {
            String txnId = request == null ? null
                    : (request.transactionId() == null ? null : request.transactionId().trim());

            // 1) 已评分流水：重复提交直接回放首次结论
            if (txnId != null) {
                var existing = ledger.findScore(txnId);
                if (existing.isPresent()) {
                    log.info("[幂等命中] txnId={} 返回首次评分结论 duplicate=true score={} version={}",
                            txnId, existing.get().totalScore(), existing.get().rulesVersion());
                    return SubmissionOutcome.scored(existing.get(), true);
                }
                var rejected = ledger.findRejection(txnId);
                if (rejected.isPresent()) {
                    log.info("[幂等命中] txnId={} 返回首次拒绝原因 duplicate=true reason={}",
                            txnId, rejected.get().reason());
                    return new SubmissionOutcome(true, null, rejected.get());
                }
            }

            // 2) 字段校验：缺失/非法/时间异常 → 可区分拒绝原因并落账
            ValidationOutcome vo = validator.validate(request, now);
            if (!vo.accepted()) {
                RejectedSubmission rejection = new RejectedSubmission(
                        txnId, vo.reason(), vo.detail(), now);
                ledger.appendRejection(rejection);
                log.warn("[流水拒绝] txnId={} reason={} detail={}",
                        txnId, vo.reason(), vo.detail());
                return SubmissionOutcome.rejected(rejection);
            }

            // 3) 新流水：用"当前激活版本"与事件时间更早的基线评分
            TransactionEvent event = vo.event();
            RuleSet rules = ruleStore.current();
            List<TransactionEvent> prior = priorEvents(event);
            ScoreResult result = engine.score(event, prior, rules, now);

            // 4) 原子入账（账本再做一次 putIfAbsent 防御）
            boolean inserted = ledger.append(event, result);
            if (!inserted) {
                ScoreResult first = ledger.findScore(event.transactionId()).orElseThrow();
                log.info("[并发去重] txnId={} 已有并发结论，采用首次结论", txnId);
                return SubmissionOutcome.scored(first, true);
            }

            // 5) 达到阈值自动开案（案件按 transactionId 幂等）
            if (result.totalScore() >= rules.autoCaseThreshold()) {
                caseService.openFor(result);
                log.info("[自动开案] txnId={} score={} >= autoCaseThreshold={}",
                        txnId, result.totalScore(), rules.autoCaseThreshold());
            }
            return SubmissionOutcome.scored(result, false);
        }
    }

    private List<TransactionEvent> priorEvents(TransactionEvent event) {
        return ledger.eventsOfAccountExcept(event.accountId(), event.transactionId())
                .stream()
                .filter(p -> p.eventTime().isBefore(event.eventTime()))
                .toList();
    }

    public ScoreResult score(String transactionId) {
        return ledger.findScore(transactionId)
                .orElseThrow(() -> new ApiException(ReviewError.TRANSACTION_NOT_FOUND,
                        "transactionId=" + transactionId));
    }

    public List<ScoreResult> scores() {
        return ledger.allScores();
    }

    public List<RejectedSubmission> rejections() {
        return ledger.allRejections();
    }

    /**
     * 历史结论复现：取存档评分引用的规则版本（旧版本永久只读），用存档基线重算，
     * 逐字段比对。任何不一致都会在报告中标注。
     */
    /** 清空账本与案件（规则版本保留），供本地场景/测试隔离使用。 */
    public void resetAll() {
        synchronized (lock) {
            ledger.reset();
            caseStore.clear();
            ruleStore.resetToV1();
            log.info("[重置] 账本、案件与规则版本已恢复初始状态");
        }
    }

    public ReplayReport replay(String transactionId) {
        ScoreResult stored = score(transactionId);
        RuleSet rules = ruleStore.requireVersion(stored.rulesVersion());
        TransactionEvent event = ledger.findEvent(transactionId)
                .orElseThrow(() -> new ApiException(ReviewError.TRANSACTION_NOT_FOUND,
                        "transactionId=" + transactionId));
        // 复现严格使用评分时的基线快照（乱序补齐的更晚到流水不会污染历史基线）
        List<TransactionEvent> baselineAtScoredTime = stored.baselineTransactionIds().stream()
                .map(id -> ledger.findEvent(id).orElseThrow())
                .toList();
        ScoreResult recomputed = engine.score(event, baselineAtScoredTime, rules,
                stored.scoredAt());

        boolean consistent = stored.totalScore() == recomputed.totalScore()
                && stored.riskLevel() == recomputed.riskLevel()
                && stored.rulesVersion().equals(recomputed.rulesVersion())
                && contributionsEqual(stored, recomputed);
        String note = consistent
                ? "重算结果与存档结论逐因子一致，版本=" + rules.version()
                : "重算结果与存档不一致，请核查规则版本与基线数据";
        if (!consistent) {
            log.error("[复现失败] txnId={} stored={} recomputed={}",
                    transactionId, stored, recomputed);
        }
        return new ReplayReport(transactionId, stored, recomputed, consistent, note);
    }

    private boolean contributionsEqual(ScoreResult a, ScoreResult b) {
        if (a.contributions().size() != b.contributions().size()) {
            return false;
        }
        for (int i = 0; i < a.contributions().size(); i++) {
            var x = a.contributions().get(i);
            var y = b.contributions().get(i);
            if (x.factor() != y.factor() || x.points() != y.points()) {
                return false;
            }
        }
        return true;
    }
}
