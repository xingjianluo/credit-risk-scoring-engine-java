package com.github.xingjianluo.creditriskscoringenginejava.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 账户级可疑活动归集案件：把同一账户在一个可配置时间窗口内
 * 达到高风险等级的多笔流水归集到同一个账户维度案件，便于运营集中跟进。
 *
 * <p>与单笔 {@link ReviewCase} 并存：每笔流水仍保留独立评分结论与单笔案件，
 * 本类只做账户维度的归集视图；归集只追加、不修改任何历史结论。
 *
 * <p>窗口采用<b>纪元对齐的固定时间桶</b>：bucketStart = eventTime 向下取整到
 * 窗口整数倍。同一账户同一桶内的高风险流水归集到同一案件，归集结果只取决于
 * 事件时间，与到达顺序（顺序/乱序/并发）无关。
 */
public class AccountCase {

    private final String caseId;
    private final String accountId;
    private final Instant windowStart;
    private final Instant windowEnd;
    private final Instant openedAt;

    /** 已归集的交易 ID（去重、保序）；计数由该集合推导，不会重复归集。 */
    private final Set<String> linkedTransactionIds = new LinkedHashSet<>();

    private CaseStatus status;
    private Instant updatedAt;
    private final List<CaseEvent> events = new ArrayList<>();

    public AccountCase(String caseId, String accountId, Instant windowStart, Instant windowEnd,
                       Instant openedAt) {
        this.caseId = caseId;
        this.accountId = accountId;
        this.windowStart = windowStart;
        this.windowEnd = windowEnd;
        this.openedAt = openedAt;
        this.status = CaseStatus.OPEN;
        this.updatedAt = openedAt;
    }

    public String getCaseId() { return caseId; }
    public String getAccountId() { return accountId; }
    public Instant getWindowStart() { return windowStart; }
    public Instant getWindowEnd() { return windowEnd; }
    public Instant getOpenedAt() { return openedAt; }
    public CaseStatus getStatus() { return status; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void setStatus(CaseStatus status) { this.status = status; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    /** 已归集交易数（案件计数的唯一来源）。 */
    public int getLinkedCount() { return linkedTransactionIds.size(); }

    public List<String> getLinkedTransactionIds() {
        synchronized (linkedTransactionIds) {
            return List.copyOf(linkedTransactionIds);
        }
    }

    /** @return true 表示本次为新归集；false 表示该流水此前已归集（幂等） */
    public boolean link(String transactionId) {
        synchronized (linkedTransactionIds) {
            return linkedTransactionIds.add(transactionId);
        }
    }

    public boolean isLinked(String transactionId) {
        synchronized (linkedTransactionIds) {
            return linkedTransactionIds.contains(transactionId);
        }
    }

    public List<CaseEvent> getEvents() {
        synchronized (events) {
            return Collections.unmodifiableList(new ArrayList<>(events));
        }
    }

    public void appendEvent(CaseEvent event) {
        synchronized (events) {
            events.add(event);
        }
    }
}
