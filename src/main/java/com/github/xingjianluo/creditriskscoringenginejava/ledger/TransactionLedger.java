package com.github.xingjianluo.creditriskscoringenginejava.ledger;

import com.github.xingjianluo.creditriskscoringenginejava.model.RejectionRecord;
import com.github.xingjianluo.creditriskscoringenginejava.model.RiskScoreResult;
import com.github.xingjianluo.creditriskscoringenginejava.model.Transaction;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 内存账本：流水 + 评分结论 + 拒绝留痕，以 transactionId 幂等。
 *
 * <p>所有结构均为并发安全容器；{@link #putIfAbsentTransaction} 保证同一交易
 * 即使在并发提交下也只入账一次，是重复/乱序/并发一致性的基础。
 */
@Component
public class TransactionLedger {

    private final ConcurrentMap<String, Transaction> transactions = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, RiskScoreResult> scores = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, RejectionRecord> rejections = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, List<Transaction>> byAccount = new ConcurrentHashMap<>();

    public boolean contains(String transactionId) {
        return transactions.containsKey(transactionId)
                || scores.containsKey(transactionId)
                || rejections.containsKey(transactionId);
    }

    /**
     * 仅当交易 ID 首次出现时入账。
     * @return 入账成功返回 null；已存在（重复提交）返回既有流水
     */
    public Transaction putIfAbsentTransaction(Transaction tx) {
        Transaction existing = transactions.putIfAbsent(tx.transactionId(), tx);
        if (existing == null) {
            byAccount.computeIfAbsent(tx.accountId(), k ->
                    java.util.Collections.synchronizedList(new ArrayList<>())).add(tx);
        }
        return existing;
    }

    public Optional<Transaction> transaction(String transactionId) {
        return Optional.ofNullable(transactions.get(transactionId));
    }

    public Optional<RiskScoreResult> scoreOf(String transactionId) {
        return Optional.ofNullable(scores.get(transactionId));
    }

    public Optional<RejectionRecord> rejectionOf(String transactionId) {
        return Optional.ofNullable(rejections.get(transactionId));
    }

    public void saveScore(RiskScoreResult result) {
        scores.putIfAbsent(result.transactionId(), result);
    }

    /**
     * 保存“已入账交易事后被判非法”的拒绝记录（乱序/并发收集阶段后校验产生），
     * 同时移除该交易，使其不出现在历史基线中。
     */
    public void saveRejectionForAcceptedTx(RejectionRecord rejection) {
        rejections.putIfAbsent(rejection.transactionId(), rejection);
        Transaction removed = transactions.remove(rejection.transactionId());
        if (removed != null) {
            List<Transaction> list = byAccount.get(removed.accountId());
            if (list != null) {
                synchronized (list) {
                    list.removeIf(t -> t.transactionId().equals(removed.transactionId()));
                }
            }
        }
    }

    public void saveRejection(RejectionRecord rejection) {
        rejections.putIfAbsent(rejection.transactionId(), rejection);
    }

    public List<Transaction> transactionsOfAccount(String accountId) {
        List<Transaction> list = byAccount.get(accountId);
        if (list == null) {
            return List.of();
        }
        synchronized (list) {
            return List.copyOf(list);
        }
    }

    public List<Transaction> allTransactions() {
        return List.copyOf(transactions.values());
    }

    public List<RiskScoreResult> allScores() {
        return List.copyOf(scores.values());
    }

    public List<RejectionRecord> allRejections() {
        return List.copyOf(rejections.values());
    }

    /** 清空账本（供模拟器/测试隔离不同到达方式时使用）。 */
    public void clear() {
        transactions.clear();
        scores.clear();
        rejections.clear();
        byAccount.clear();
    }
}
