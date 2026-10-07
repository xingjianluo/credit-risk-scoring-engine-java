package com.github.xingjianluo.creditriskscoringenginejava.service;

import com.github.xingjianluo.creditriskscoringenginejava.baseline.AccountBaselineService;
import com.github.xingjianluo.creditriskscoringenginejava.caseflow.AccountCaseService;
import com.github.xingjianluo.creditriskscoringenginejava.caseflow.ReviewCaseService;
import com.github.xingjianluo.creditriskscoringenginejava.ledger.TransactionLedger;
import com.github.xingjianluo.creditriskscoringenginejava.model.AccountCase;
import com.github.xingjianluo.creditriskscoringenginejava.model.BaselineSnapshot;
import com.github.xingjianluo.creditriskscoringenginejava.model.FactorContribution;
import com.github.xingjianluo.creditriskscoringenginejava.model.RejectionRecord;
import com.github.xingjianluo.creditriskscoringenginejava.model.ReviewCase;
import com.github.xingjianluo.creditriskscoringenginejava.model.RiskLevel;
import com.github.xingjianluo.creditriskscoringenginejava.model.RiskScoreResult;
import com.github.xingjianluo.creditriskscoringenginejava.model.RuleContext;
import com.github.xingjianluo.creditriskscoringenginejava.model.RuleVersion;
import com.github.xingjianluo.creditriskscoringenginejava.model.SubmissionOutcome;
import com.github.xingjianluo.creditriskscoringenginejava.model.Transaction;
import com.github.xingjianluo.creditriskscoringenginejava.rule.RiskRuleEngine;
import com.github.xingjianluo.creditriskscoringenginejava.validation.TransactionValidator;
import com.github.xingjianluo.creditriskscoringenginejava.version.RuleVersionRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 单笔评分管线：校验 -&gt; 基线 -&gt; 多因子评分 -&gt; 持久化 -&gt; 自动开案。
 *
 * <p>本组件不负责幂等与收集排序，由 {@link RiskScoringService} 保证
 * “先收集去重、再按事件时间顺序逐笔调用本管线”，从而使乱序/并发与顺序到达
 * 看到完全相同的历史基线。
 */
@Component
public class ScoringPipeline {

    private static final Logger log = LoggerFactory.getLogger(ScoringPipeline.class);

    private final TransactionLedger ledger;
    private final TransactionValidator validator;
    private final AccountBaselineService baselineService;
    private final RiskRuleEngine ruleEngine;
    private final RuleVersionRegistry versionRegistry;
    private final ReviewCaseService caseService;
    private final AccountCaseService accountCaseService;
    private final Clock clock;

    public ScoringPipeline(TransactionLedger ledger, TransactionValidator validator,
                           AccountBaselineService baselineService, RiskRuleEngine ruleEngine,
                           RuleVersionRegistry versionRegistry, ReviewCaseService caseService,
                           AccountCaseService accountCaseService, Clock clock) {
        this.ledger = ledger;
        this.validator = validator;
        this.baselineService = baselineService;
        this.ruleEngine = ruleEngine;
        this.versionRegistry = versionRegistry;
        this.caseService = caseService;
        this.accountCaseService = accountCaseService;
        this.clock = clock;
    }

    /**
     * 对已完成收集去重的一笔流水执行评分或拒绝。
     *
     * @param preAccepted true 表示流水已在收集阶段入账（此时若发现非法，
     *                    需要从账本移除并关联拒绝记录）
     */
    public SubmissionOutcome evaluate(Transaction tx, boolean preAccepted) {
        RejectionRecord rejection = validator.validate(tx);
        if (rejection != null) {
            if (preAccepted) {
                ledger.saveRejectionForAcceptedTx(rejection);
            } else {
                ledger.saveRejection(rejection);
            }
            log.warn("[REJECT] tx={} account={} reason={} detail={} fingerprint={}",
                    rejection.transactionId(), rejection.accountId(), rejection.reason(),
                    rejection.detail(), rejection.fingerprint());
            return SubmissionOutcome.rejected(rejection, false);
        }
        if (!preAccepted) {
            ledger.putIfAbsentTransaction(tx);
        }

        BaselineSnapshot baseline = baselineService.baselineFor(tx);
        RuleVersion version = versionRegistry.current();
        RuleContext ctx = new RuleContext(tx, baseline);
        List<FactorContribution> contributions = ruleEngine.evaluateAll(ctx, version);
        int total = ruleEngine.sum(contributions);
        RiskLevel level = ruleEngine.level(total, version);

        RiskScoreResult result = new RiskScoreResult(tx.transactionId(), tx.accountId(), total, level,
                version.version(), contributions, baseline, Instant.now(clock), tx.eventTime());
        ledger.saveScore(result);

        Optional<ReviewCase> opened = caseService.openCaseIfNeeded(result);
        List<String> caseIds = opened.map(c -> List.of(c.getCaseId())).orElse(List.of());

        // 账户级归集：窗口内高风险笔数达阈值时关联到账户维度案件（只追加关联，不改历史结论）
        Optional<AccountCase> accountCase = accountCaseService.aggregateIfNeeded(result);
        String accountCaseId = accountCase.map(AccountCase::getCaseId).orElse("无");

        log.info("[SCORE] tx={} account={} 输入信号(amount={},ccy={},time={},cp={},country={},channel={}) "
                        + "基线(prior={},avg={},window={}) 命中规则={} 判定依据=总分{}/等级{}/版本{} 案件={} 账户归集案={}",
                tx.transactionId(), tx.accountId(), tx.amount(), tx.currency(), tx.eventTime(),
                tx.counterpartyAccount(), tx.counterpartyCountry(), tx.channel(),
                baseline.priorCount(), baseline.avgAmount(), baseline.recentWindowCount(),
                contributions.stream().map(c -> c.ruleCode() + "+" + c.points()).toList(),
                total, level, version.version(),
                caseIds.isEmpty() ? "无" : caseIds.get(0), accountCaseId);

        return SubmissionOutcome.scored(result, false, caseIds);
    }

    public List<String> caseIdsOf(String txId) {
        return caseService.findByTransactionId(txId).map(c -> List.of(c.getCaseId())).orElse(List.of());
    }

    /** 按指定（旧）版本重新计算（不持久化），用于复现对比。 */
    public RiskScoreResult recomputeWithVersion(Transaction tx, String versionName) {
        RuleVersion version = versionRegistry.get(versionName);
        BaselineSnapshot baseline = baselineService.baselineFor(tx);
        RuleContext ctx = new RuleContext(tx, baseline);
        List<FactorContribution> contributions = ruleEngine.evaluateAll(ctx, version);
        int total = ruleEngine.sum(contributions);
        RiskLevel level = ruleEngine.level(total, version);
        log.info("[RECOMPUTE] tx={} 以版本{}复算 命中规则={} 总分{}/等级{}",
                tx.transactionId(), versionName,
                contributions.stream().map(c -> c.ruleCode() + "+" + c.points()).toList(), total, level);
        return new RiskScoreResult(tx.transactionId(), tx.accountId(), total, level,
                version.version(), contributions, baseline, Instant.now(clock), tx.eventTime());
    }
}
