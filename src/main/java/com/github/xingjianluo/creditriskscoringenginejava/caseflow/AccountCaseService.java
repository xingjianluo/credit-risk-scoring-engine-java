package com.github.xingjianluo.creditriskscoringenginejava.caseflow;

import com.github.xingjianluo.creditriskscoringenginejava.model.AccountCase;
import com.github.xingjianluo.creditriskscoringenginejava.model.CaseEvent;
import com.github.xingjianluo.creditriskscoringenginejava.model.RiskLevel;
import com.github.xingjianluo.creditriskscoringenginejava.model.RiskScoreResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 账户级可疑活动归集服务。
 *
 * <p>归集规则：同一账户在以当前流水 eventTime 为右端点的滑动窗口
 * （{@code app.aggregation.window}，默认 30 分钟）内，达到 HIGH（含）以上的
 * 流水笔数达到 {@code app.aggregation.threshold}（默认 3）时，把窗口内这些流水
 * 关联到该账户唯一的账户级审查案件上；不足阈值时只记录命中、不开案。
 *
 * <p>一致性设计：
 * <ul>
 *   <li>每个账户一把锁，归集判定与关联按账户串行；</li>
 *   <li>命中记录与关联都按 transactionId 去重：重复提交/同批重复不会重复归集；</li>
 *   <li>归集只依赖“已评分结论”（eventTime + 等级），评分层保证同批按 eventTime
 *       顺序评估，因此顺序/乱序/并发三种到达方式归集结果与案件计数一致；</li>
 *   <li>只追加关联关系与审计事件，绝不修改历史评分结论与单笔案件。</li>
 * </ul>
 */
@Service
public class AccountCaseService {

    private static final Logger log = LoggerFactory.getLogger(AccountCaseService.class);

    /** 达到该风险等级（含）才参与归集。 */
    public static final RiskLevel AGGREGATION_THRESHOLD_LEVEL = RiskLevel.HIGH;

    /** 一笔达到归集等级的高风险评分命中。 */
    private record HighRiskHit(String txId, Instant eventTime, RiskLevel level, int score) {
    }

    private final Clock clock;
    private final ReviewCaseService reviewCaseService;
    private final Duration window;
    private final int threshold;

    private final ConcurrentMap<String, AccountCase> casesById = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, ConcurrentMap<String, AccountCase>> casesByAccount =
            new ConcurrentHashMap<>();
    /** 每账户的高风险命中流水（按评分完成顺序追加，按 txId 去重）。 */
    private final ConcurrentMap<String, List<HighRiskHit>> hitsByAccount = new ConcurrentHashMap<>();
    /** 每账户已归集的交易 ID（幂等：同一流水只关联一次）。 */
    private final ConcurrentMap<String, Set<String>> linkedTxByAccount = new ConcurrentHashMap<>();
    /** 每账户一把锁：归集判定与关联按账户串行。 */
    private final ConcurrentMap<String, Object> accountLocks = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong();

    public AccountCaseService(Clock clock, ReviewCaseService reviewCaseService,
                              @Value("${app.aggregation.window:PT30M}") Duration window,
                              @Value("${app.aggregation.threshold:3}") int threshold) {
        this.clock = clock;
        this.reviewCaseService = reviewCaseService;
        this.window = window;
        this.threshold = threshold;
    }

    /**
     * 评分完成后尝试归集。未达归集等级或窗口内笔数不足阈值时返回 empty。
     * 幂等：同一交易重复进入直接返回既有归集结果，不重复关联、不重复开案。
     */
    public Optional<AccountCase> aggregateIfNeeded(RiskScoreResult score) {
        if (score == null || score.level().ordinal() < AGGREGATION_THRESHOLD_LEVEL.ordinal()) {
            return Optional.empty();
        }
        String accountId = score.accountId();
        synchronized (accountLocks.computeIfAbsent(accountId, k -> new Object())) {
            List<HighRiskHit> hits = hitsByAccount.computeIfAbsent(accountId,
                    k -> new ArrayList<>());
            Set<String> linked = linkedTxByAccount.computeIfAbsent(accountId,
                    k -> new HashSet<>());

            // 重复归集防护：命中只记录一次
            if (hits.stream().noneMatch(h -> h.txId().equals(score.transactionId()))) {
                hits.add(new HighRiskHit(score.transactionId(), score.eventTime(),
                        score.level(), score.totalScore()));
            } else {
                log.info("[ACCT-CASE] tx={} 已记录命中，跳过重复归集 account={}",
                        score.transactionId(), accountId);
                return linked.contains(score.transactionId()) ? currentCaseOf(accountId)
                        : Optional.empty();
            }

            // 以当前流水 eventTime 为右端点的滑动窗口内统计高风险笔数
            Instant t = score.eventTime();
            Instant from = t.minus(window);
            List<HighRiskHit> inWindow = hits.stream()
                    .filter(h -> h.eventTime().isAfter(from) && !h.eventTime().isAfter(t))
                    .toList();
            if (inWindow.size() < threshold) {
                log.info("[ACCT-CASE] account={} 窗口({} 至 {})高风险笔数={} < 阈值{}，暂不开案",
                        accountId, from, t, inWindow.size(), threshold);
                return Optional.empty();
            }

            AccountCase accountCase = getOrCreateCase(accountId, score, inWindow.size(), t);

            // 关联窗口内尚未归集的流水（含其单笔案件），按交易幂等
            Instant now = Instant.now(clock);
            for (HighRiskHit hit : inWindow) {
                if (!linked.add(hit.txId())) {
                    continue;
                }
                String reviewCaseId = reviewCaseService.findByTransactionId(hit.txId())
                        .map(c -> c.getCaseId()).orElse(null);
                accountCase.link(hit.txId(), reviewCaseId, hit.level(), hit.score(),
                        new CaseEvent(accountCase.getCaseId(), null, null, "system", null,
                                "归集高风险流水 tx=" + hit.txId()
                                        + (reviewCaseId == null ? "" : "（单笔案件 " + reviewCaseId + "）")
                                        + "，等级 " + hit.level() + "，窗口内第 "
                                        + accountCase.getTransactionIds().size() + 1 + " 笔", now),
                        now);
                log.info("[ACCT-CASE] 归集 account={} caseId={} tx={} reviewCase={} level={} score={}",
                        accountId, accountCase.getCaseId(), hit.txId(), reviewCaseId,
                        hit.level(), hit.score());
            }
            return Optional.of(accountCase);
        }
    }

    private AccountCase getOrCreateCase(String accountId, RiskScoreResult trigger,
                                    int windowCount, Instant windowEnd) {
        ConcurrentMap<String, AccountCase> byAccount = casesByAccount.computeIfAbsent(accountId,
                k -> new ConcurrentHashMap<>());
        if (!byAccount.isEmpty()) {
            return byAccount.values().iterator().next();
        }
        Instant now = Instant.now(clock);
        AccountCase created = new AccountCase(nextCaseId(), accountId, now);
        AccountCase existing = byAccount.putIfAbsent(created.getCaseId(), created);
        if (existing != null) {
            return existing;
        }
        casesById.put(created.getCaseId(), created);
        created.appendEvent(new CaseEvent(created.getCaseId(), null, null, "system", null,
                "账户 " + accountId + " 在窗口（" + window + "，截至 " + windowEnd + "）内出现 "
                        + windowCount + " 笔 " + AGGREGATION_THRESHOLD_LEVEL
                        + "+ 流水（阈值 " + threshold + "），触发账户级归集开案；触发流水 "
                        + trigger.transactionId() + "（" + trigger.level() + "，"
                        + trigger.totalScore() + " 分，规则版本 " + trigger.ruleVersion() + "）", now));
        log.info("[ACCT-CASE] 账户级开案 caseId={} account={} 窗口={} 窗口内高风险笔数={} 阈值={} 触发tx={}",
                created.getCaseId(), accountId, window, windowCount, threshold,
                trigger.transactionId());
        return created;
    }

    private Optional<AccountCase> currentCaseOf(String accountId) {
        return Optional.ofNullable(casesByAccount.get(accountId))
                .filter(m -> !m.isEmpty())
                .map(m -> m.values().iterator().next());
    }

    public List<AccountCase> all() {
        return List.copyOf(casesById.values());
    }

    public List<AccountCase> findByAccount(String accountId) {
        ConcurrentMap<String, AccountCase> byAccount = casesByAccount.get(accountId);
        return byAccount == null ? List.of() : List.copyOf(byAccount.values());
    }

    public Optional<AccountCase> find(String caseId) {
        return Optional.ofNullable(casesById.get(caseId));
    }

    /** 清空归集状态（供模拟器/测试隔离不同到达方式时使用）。 */
    public void clear() {
        casesById.clear();
        casesByAccount.clear();
        hitsByAccount.clear();
        linkedTxByAccount.clear();
        accountLocks.clear();
        sequence.set(0);
    }

    private String nextCaseId() {
        return String.format("ACCT-CASE-%06d", sequence.incrementAndGet());
    }
}
