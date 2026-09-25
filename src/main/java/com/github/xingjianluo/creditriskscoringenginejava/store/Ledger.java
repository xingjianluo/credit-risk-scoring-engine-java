package com.github.xingjianluo.creditriskscoringenginejava.store;

import com.github.xingjianluo.creditriskscoringenginejava.domain.RejectedSubmission;
import com.github.xingjianluo.creditriskscoringenginejava.domain.ScoreResult;
import com.github.xingjianluo.creditriskscoringenginejava.domain.TransactionEvent;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;

/**
 * 内存账本（线程安全）。
 *
 * <p>已接受流水与评分结论以 transactionId 为键，重复提交直接命中既有记录，
 * 不产生第二次计分。历史基线不由外部增量维护，而是按 eventTime 过滤既有流水重建，
 * 因此乱序到达只会"补全"历史，已得出的结论永不改变。
 */
@Component
public class Ledger {

    private final ConcurrentMap<String, TransactionEvent> events = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, ScoreResult> scores = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, RejectedSubmission> rejections = new ConcurrentHashMap<>();

    public Optional<TransactionEvent> findEvent(String transactionId) {
        return Optional.ofNullable(events.get(transactionId));
    }

    public Optional<ScoreResult> findScore(String transactionId) {
        return Optional.ofNullable(scores.get(transactionId));
    }

    public Optional<RejectedSubmission> findRejection(String transactionId) {
        return Optional.ofNullable(rejections.get(transactionId));
    }

    /** 仅在首次出现时入账；重复键保持既有结论不变（幂等）。 */
    public boolean append(TransactionEvent event, ScoreResult result) {
        if (events.putIfAbsent(event.transactionId(), event) != null) {
            return false;
        }
        scores.put(result.transactionId(), result);
        return true;
    }

    private static final String MISSING_ID_KEY = "__MISSING_TRANSACTION_ID__";

    public void appendRejection(RejectedSubmission rejection) {
        // 拒绝记录也按 transactionId 去重，保留第一次的拒绝原因；
        // transactionId 本身缺失时使用哨兵键（detail 仍可区分每次输入）。
        String key = rejection.transactionId() == null
                ? MISSING_ID_KEY : rejection.transactionId();
        rejections.putIfAbsent(key, rejection);
    }

    /**
     * 某账户除指定流水外的全部已接受流水（按事件时间升序）。
     * 调用方按事件时间过滤"更早"的流水；包含后到补齐的更早事件，
     * 以保证乱序补全后复现结果反映完整历史。
     */
    public List<TransactionEvent> eventsOfAccountExcept(String accountId,
                                                        String excludeTransactionId) {
        return events.values().stream()
                .filter(e -> e.accountId().equals(accountId))
                .filter(e -> !e.transactionId().equals(excludeTransactionId))
                .sorted(java.util.Comparator.comparing(TransactionEvent::eventTime))
                .collect(Collectors.toList());
    }

    public List<TransactionEvent> eventsOf(String accountId) {
        return events.values().stream()
                .filter(e -> e.accountId().equals(accountId))
                .sorted(java.util.Comparator.comparing(TransactionEvent::eventTime))
                .collect(Collectors.toList());
    }

    public List<ScoreResult> allScores() {
        return scores.values().stream()
                .sorted(java.util.Comparator.comparing(ScoreResult::scoredAt))
                .collect(Collectors.toList());
    }

    public List<RejectedSubmission> allRejections() {
        return List.copyOf(rejections.values());
    }

    public int reset() {
        int n = events.size() + rejections.size();
        events.clear();
        scores.clear();
        rejections.clear();
        return n;
    }
}
