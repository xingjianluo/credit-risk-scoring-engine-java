package com.github.xingjianluo.creditriskscoringenginejava.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 账户级可疑活动归集案件：同一账户在可配置时间窗口内出现多笔高风险流水时，
 * 把这些流水的评分结论与单笔案件关联到同一个账户维度案件上，便于运营集中跟进。
 *
 * <p>只追加关联、不修改任何已生成的评分结论与单笔案件审计信息。
 */
public class AccountCase {

    private final String caseId;
    private final String accountId;
    private final Instant openedAt;

    /** 已归集的交易 ID（按归集顺序，去重）。 */
    private final List<String> transactionIds = new ArrayList<>();
    /** 已归集的单笔审查案件 ID（与 transactionIds 对应，可能为空元素较少）。 */
    private final List<String> reviewCaseIds = new ArrayList<>();
    /** 归集依据审计链（开窗 / 每次关联）。 */
    private final List<CaseEvent> events = new ArrayList<>();

    private RiskLevel maxLevel;
    private int maxScore;
    private Instant updatedAt;

    public AccountCase(String caseId, String accountId, Instant openedAt) {
        this.caseId = caseId;
        this.accountId = accountId;
        this.openedAt = openedAt;
        this.updatedAt = openedAt;
        this.maxLevel = RiskLevel.LOW;
        this.maxScore = 0;
    }

    public String getCaseId() { return caseId; }
    public String getAccountId() { return accountId; }
    public Instant getOpenedAt() { return openedAt; }
    public RiskLevel getMaxLevel() { return maxLevel; }
    public int getMaxScore() { return maxScore; }
    public Instant getUpdatedAt() { return updatedAt; }

    public List<String> getTransactionIds() { return Collections.unmodifiableList(transactionIds); }
    public List<String> getReviewCaseIds() { return Collections.unmodifiableList(reviewCaseIds); }
    public List<CaseEvent> getEvents() { return Collections.unmodifiableList(events); }

    /** 关联一笔高风险流水及其单笔案件（调用方保证按账户串行且按交易去重）。 */
    public void link(String transactionId, String reviewCaseId, RiskLevel level, int score,
                     CaseEvent auditEvent, Instant now) {
        transactionIds.add(transactionId);
        if (reviewCaseId != null) {
            reviewCaseIds.add(reviewCaseId);
        }
        if (level.ordinal() > maxLevel.ordinal()) {
            maxLevel = level;
        }
        maxScore = Math.max(maxScore, score);
        updatedAt = now;
        events.add(auditEvent);
    }

    public void appendEvent(CaseEvent event) {
        events.add(event);
    }
}
