package com.github.xingjianluo.creditriskscoringenginejava.caseflow;

import com.github.xingjianluo.creditriskscoringenginejava.exception.CaseNotFoundException;
import com.github.xingjianluo.creditriskscoringenginejava.exception.InvalidRequestException;
import com.github.xingjianluo.creditriskscoringenginejava.model.CaseEvent;
import com.github.xingjianluo.creditriskscoringenginejava.model.CaseStatus;
import com.github.xingjianluo.creditriskscoringenginejava.model.DispositionRequest;
import com.github.xingjianluo.creditriskscoringenginejava.model.ReviewCase;
import com.github.xingjianluo.creditriskscoringenginejava.model.RiskLevel;
import com.github.xingjianluo.creditriskscoringenginejava.model.RiskScoreResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 可疑活动审查案件服务。
 *
 * <p>开案按 transactionId 幂等（{@code casesByTransaction} + computeIfAbsent），
 * 重复或乱序到达的同一高风险流水不会重复开案；
 * 处置对单个案件对象加锁，保证并发提交下状态流转与计数最终一致。
 */
@Service
public class ReviewCaseService {

    private static final Logger log = LoggerFactory.getLogger(ReviewCaseService.class);

    /** 达到该风险等级（含）才开案。 */
    public static final RiskLevel CASE_THRESHOLD = RiskLevel.HIGH;

    private final CaseStateMachine stateMachine;
    private final Clock clock;

    private final ConcurrentMap<String, ReviewCase> casesById = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String> txToCase = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong();

    public ReviewCaseService(CaseStateMachine stateMachine, Clock clock) {
        this.stateMachine = stateMachine;
        this.clock = clock;
    }

    /**
     * 依据评分结果在达到开案阈值时开案；已存在则返回既有案件（不重复开案）。
     * 未达阈值返回 empty。
     */
    public Optional<ReviewCase> openCaseIfNeeded(RiskScoreResult score) {
        if (score == null || score.level().ordinal() < CASE_THRESHOLD.ordinal()) {
            return Optional.empty();
        }
        ReviewCase created = new ReviewCase(nextCaseId(), score.transactionId(), score.accountId(),
                score.totalScore(), score.level(), score.ruleVersion(), Instant.now(clock));
        String existingCaseId = txToCase.computeIfAbsent(score.transactionId(), k -> created.getCaseId());
        if (!existingCaseId.equals(created.getCaseId())) {
            log.info("[CASE] 交易 {} 已有案件 {}，跳重复开案", score.transactionId(), existingCaseId);
            return Optional.of(casesById.get(existingCaseId));
        }
        casesById.put(created.getCaseId(), created);
        created.appendEvent(new CaseEvent(created.getCaseId(), null, CaseStatus.OPEN,
                "system", null, "风险等级 " + score.level() + "（" + score.totalScore()
                        + "分，规则版本 " + score.ruleVersion() + "）自动开案", Instant.now(clock)));
        log.info("[CASE] 自动开案 caseId={} tx={} score={} level={} version={}",
                created.getCaseId(), score.transactionId(), score.totalScore(),
                score.level(), score.ruleVersion());
        return Optional.of(created);
    }

    /** 应用处置动作；非法迁移/权限不足由状态机抛出可区分异常。 */
    public ReviewCase apply(DispositionRequest request) {
        if (request == null || request.caseId() == null || request.caseId().isBlank()) {
            throw new InvalidRequestException("INVALID_DISPOSITION_REQUEST", "处置请求缺失 caseId");
        }
        if (request.action() == null) {
            throw new InvalidRequestException("INVALID_DISPOSITION_REQUEST", "处置请求缺失 action");
        }
        ReviewCase target = get(request.caseId());
        synchronized (target) {
            CaseStatus from = target.getStatus();
            CaseStatus to = stateMachine.transition(target, request);
            Instant now = Instant.now(clock);
            target.setStatus(to);
            if (request.action() == com.github.xingjianluo.creditriskscoringenginejava.model.CaseAction.ASSIGN) {
                target.setAssignee(request.reviewer());
            }
            target.setUpdatedAt(now);
            target.appendEvent(new CaseEvent(target.getCaseId(), from, to,
                    request.reviewer(), request.role(), request.note(), now));
            log.info("[CASE] 处置 caseId={} action={} {}->{} reviewer={} role={} note={}",
                    target.getCaseId(), request.action(), from, to,
                    request.reviewer(), request.role(), request.note());
            return target;
        }
    }

    public ReviewCase get(String caseId) {
        ReviewCase c = casesById.get(caseId);
        if (c == null) {
            throw new CaseNotFoundException(caseId);
        }
        return c;
    }

    /** 按案件号查找（不存在返回 empty，不抛异常）。 */
    public Optional<ReviewCase> find(String caseId) {
        return Optional.ofNullable(caseId == null ? null : casesById.get(caseId));
    }

    public Optional<ReviewCase> findByTransactionId(String transactionId) {
        return Optional.ofNullable(txToCase.get(transactionId)).map(casesById::get);
    }

    public List<ReviewCase> all() {
        return List.copyOf(casesById.values());
    }

    public List<CaseEvent> events(String caseId) {
        return get(caseId).getEvents();
    }

    /** 清空内存案件（供模拟器/测试隔离不同到达方式时使用）。 */
    public void clear() {
        casesById.clear();
        txToCase.clear();
        sequence.set(0);
    }

    private String nextCaseId() {
        return String.format("CASE-%06d", sequence.incrementAndGet());
    }
}
