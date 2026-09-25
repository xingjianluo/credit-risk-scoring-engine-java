package com.github.xingjianluo.creditriskscoringenginejava.store;

import com.github.xingjianluo.creditriskscoringenginejava.domain.ReviewCase;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 审查案件内存仓库（线程安全）。案件同时按 caseId 与 transactionId 建索引，
 * transactionId 上的 putIfAbsent 保证可疑流水不会重复开案。
 */
@Component
public class CaseStore {

    private final ConcurrentMap<String, ReviewCase> byCaseId = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String> txnIndex = new ConcurrentHashMap<>();

    public Optional<ReviewCase> findByCaseId(String caseId) {
        return Optional.ofNullable(byCaseId.get(caseId));
    }

    public Optional<ReviewCase> findByTransactionId(String transactionId) {
        return Optional.ofNullable(txnIndex.get(transactionId))
                .map(byCaseId::get);
    }

    /**
     * 幂等创建：同一 transactionId 已存在案件时返回既有案件（返回值的 caseId 可用于区分）。
     */
    public ReviewCase createIfAbsent(ReviewCase candidate) {
        synchronized (this) {
            String existingCaseId = txnIndex.putIfAbsent(
                    candidate.getTransactionId(), candidate.getCaseId());
            if (existingCaseId != null) {
                return byCaseId.get(existingCaseId);
            }
            byCaseId.put(candidate.getCaseId(), candidate);
            return candidate;
        }
    }

    public void clear() {
        synchronized (this) {
            byCaseId.clear();
            txnIndex.clear();
        }
    }

    public List<ReviewCase> all() {
        return byCaseId.values().stream()
                .sorted(Comparator.comparing(ReviewCase::getOpenedAt))
                .toList();
    }
}
