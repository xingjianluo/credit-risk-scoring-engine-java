package com.github.xingjianluo.creditriskscoringenginejava.caseflow;

import com.github.xingjianluo.creditriskscoringenginejava.exception.CaseNotFoundException;
import com.github.xingjianluo.creditriskscoringenginejava.model.AccountCase;
import com.github.xingjianluo.creditriskscoringenginejava.model.CaseEvent;
import com.github.xingjianluo.creditriskscoringenginejava.model.CaseStatus;
import com.github.xingjianluo.creditriskscoringenginejava.model.DispositionRequest;
import com.github.xingjianluo.creditriskscoringenginejava.model.RiskScoreResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 账户级可疑活动归集服务。
 *
 * <p>同一账户在一个可配置时间窗口（{@code risk.account-case.window}，默认 1 小时，
 * 纪元对齐固定时间桶）内出现多笔达到 {@link ReviewCaseService#CASE_THRESHOLD}
 * 的流水时，把它们归集到同一个账户维度案件；每笔流水的独立评分结论与单笔案件不受影响。
 *
 * <p>一致性设计：
 * <ul>
 *   <li>时间桶只由 eventTime 推导，归集结果与到达顺序（顺序/乱序/并发）无关；</li>
 *   <li>{@code txToCase} 保证同一交易只归集一次（重复提交幂等，不重复计数）；</li>
 *   <li>每账户一把锁串行化归集，并发提交下案件计数与串行结果一致；</li>
 *   <li>归集只追加新案件/新关联，从不修改历史评分结论与单笔案件审计。</li>
 * </ul>
 */
@Service
public class AccountCaseService {

    private static final Logger log = LoggerFactory.getLogger(AccountCaseService.class);

    private final CaseStateMachine stateMachine;
    private final Clock clock;
    private final Duration window;

    private final ConcurrentMap<String, AccountCase> casesById = new ConcurrentHashMap<>();
    /** (accountId, bucketStart) -> caseId：同一时间桶至多一个账户案件。 */
    private final ConcurrentMap<BucketKey, String> bucketToCase = new ConcurrentHashMap<>();
    /** transactionId -> caseId：归集幂等表，重复提交不重复归集。 */
    private final ConcurrentMap<String, String> txToCase = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Object> accountLocks = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong();

    public AccountCaseService(CaseStateMachine stateMachine, Clock clock,
                              @Value("${risk.account-case.window:PT1H}") Duration window) {
        this.stateMachine = stateMachine;
        this.clock = clock;
        this.window = window;
    }

    /**
     * 将达到归集阈值的评分结果关联到账户维度案件；未达阈值返回 empty。
     * 同一交易重复调用返回既有归集，不重复开案、不重复计数。
     */
    public Optional<AccountCase> linkIfNeeded(RiskScoreResult score) {
        if (score == null || score.level().ordinal() < ReviewCaseService.CASE_THRESHOLD.ordinal()) {
            return Optional.empty();
        }
        String already = txToCase.get(score.transactionId());
        if (already != null) {
            log.info("[ACCT-CASE] 交易 {} 已归集到 {}，跳过重复归集", score.transactionId(), already);
            return Optional.of(casesById.get(already));
        }
        synchronized (accountLocks.computeIfAbsent(score.accountId(), k -> new Object())) {
            String existing = txToCase.get(score.transactionId());
            if (existing != null) {
                return Optional.of(casesById.get(existing));
            }
            Instant bucketStart = bucketStart(score.eventTime());
            BucketKey key = new BucketKey(score.accountId(), bucketStart);
            String caseId = bucketToCase.computeIfAbsent(key, k -> {
                AccountCase created = new AccountCase(nextCaseId(), score.accountId(),
                        bucketStart, bucketStart.plus(window), Instant.now(clock));
                casesById.put(created.getCaseId(), created);
                created.appendEvent(new CaseEvent(created.getCaseId(), null, CaseStatus.OPEN,
                        "system", null, "账户 " + score.accountId() + " 在窗口 [" + bucketStart
                                + ", " + bucketStart.plus(window) + ") 内出现高风险流水，自动归集开案",
                        Instant.now(clock)));
                log.info("[ACCT-CASE] 归集开案 caseId={} account={} 窗口=[{}, {})",
                        created.getCaseId(), score.accountId(), bucketStart, bucketStart.plus(window));
                return created.getCaseId();
            });
            AccountCase target = casesById.get(caseId);
            if (target.link(score.transactionId())) {
                target.setUpdatedAt(Instant.now(clock));
                target.appendEvent(new CaseEvent(target.getCaseId(), target.getStatus(),
                        target.getStatus(), "system", null,
                        "归集流水 " + score.transactionId() + "（" + score.level() + "，"
                                + score.totalScore() + "分，规则版本 " + score.ruleVersion() + "）",
                        Instant.now(clock)));
                log.info("[ACCT-CASE] 归集 caseId={} tx={} level={} score={} 已归集笔数={}",
                        caseId, score.transactionId(), score.level(), score.totalScore(),
                        target.getLinkedCount());
            }
            txToCase.put(score.transactionId(), caseId);
            return Optional.of(target);
        }
    }

    /** 账户案件处置：复用同一状态机（合法迁移/权限校验与单笔案件一致）。 */
    public AccountCase apply(DispositionRequest request) {
        AccountCase target = get(request.caseId());
        synchronized (target) {
            CaseStatus from = target.getStatus();
            CaseStatus to = stateMachine.transition(from, request);
            Instant now = Instant.now(clock);
            target.setStatus(to);
            target.setUpdatedAt(now);
            target.appendEvent(new CaseEvent(target.getCaseId(), from, to,
                    request.reviewer(), request.role(), request.note(), now));
            log.info("[ACCT-CASE] 处置 caseId={} action={} {}->{} reviewer={} role={}",
                    target.getCaseId(), request.action(), from, to, request.reviewer(), request.role());
            return target;
        }
    }

    public AccountCase get(String caseId) {
        AccountCase c = casesById.get(caseId);
        if (c == null) {
            throw new CaseNotFoundException(caseId);
        }
        return c;
    }

    public Optional<AccountCase> findByTransactionId(String transactionId) {
        return Optional.ofNullable(txToCase.get(transactionId)).map(casesById::get);
    }

    public List<AccountCase> all() {
        return List.copyOf(casesById.values());
    }

    public List<CaseEvent> events(String caseId) {
        return get(caseId).getEvents();
    }

    /** 归集窗口（供测试与文档展示）。 */
    public Duration window() {
        return window;
    }

    /** 清空（供模拟器/测试隔离不同到达方式时使用）。 */
    public void clear() {
        casesById.clear();
        bucketToCase.clear();
        txToCase.clear();
        accountLocks.clear();
        sequence.set(0);
    }

    /** 纪元对齐固定时间桶起点：只由事件时间决定，与到达顺序无关。 */
    private Instant bucketStart(Instant eventTime) {
        long windowMs = window.toMillis();
        long bucketMs = Math.floorDiv(eventTime.toEpochMilli(), windowMs) * windowMs;
        return Instant.ofEpochMilli(bucketMs);
    }

    private String nextCaseId() {
        return String.format("ACCT-CASE-%06d", sequence.incrementAndGet());
    }

    private record BucketKey(String accountId, Instant bucketStart) {
    }
}
