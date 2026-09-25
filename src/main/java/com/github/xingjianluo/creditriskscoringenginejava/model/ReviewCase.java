package com.github.xingjianluo.creditriskscoringenginejava.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 可疑活动审查案件（可变状态 + 不可变审计事件链）。同一交易至多一个案件。
 */
public class ReviewCase {

    private final String caseId;
    private final String transactionId;
    private final String accountId;
    private final int riskScore;
    private final RiskLevel riskLevel;
    private final String ruleVersion;
    private final Instant openedAt;

    private CaseStatus status;
    private String assignee;
    private Instant updatedAt;
    private final List<CaseEvent> events = new ArrayList<>();

    public ReviewCase(String caseId, String transactionId, String accountId,
                      int riskScore, RiskLevel riskLevel, String ruleVersion, Instant openedAt) {
        this.caseId = caseId;
        this.transactionId = transactionId;
        this.accountId = accountId;
        this.riskScore = riskScore;
        this.riskLevel = riskLevel;
        this.ruleVersion = ruleVersion;
        this.openedAt = openedAt;
        this.status = CaseStatus.OPEN;
        this.updatedAt = openedAt;
    }

    public String getCaseId() { return caseId; }
    public String getTransactionId() { return transactionId; }
    public String getAccountId() { return accountId; }
    public int getRiskScore() { return riskScore; }
    public RiskLevel getRiskLevel() { return riskLevel; }
    public String getRuleVersion() { return ruleVersion; }
    public Instant getOpenedAt() { return openedAt; }
    public CaseStatus getStatus() { return status; }
    public String getAssignee() { return assignee; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void setStatus(CaseStatus status) { this.status = status; }
    public void setAssignee(String assignee) { this.assignee = assignee; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    public List<CaseEvent> getEvents() { return Collections.unmodifiableList(events); }

    public void appendEvent(CaseEvent event) { events.add(event); }
}
