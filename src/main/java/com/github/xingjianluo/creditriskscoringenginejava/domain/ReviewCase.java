package com.github.xingjianluo.creditriskscoringenginejava.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 可疑流水对应的审查案件。初始状态 OPEN，CLOSED/DISMISSED 为终态。
 */
public final class ReviewCase {
    private final String caseId;
    private final String transactionId;
    private final String accountId;
    private final int scoreSnapshot;
    private final String rulesVersion;
    private final Instant openedAt;
    private CaseStatus status;
    private String assignee;
    private String closeReason;
    private final List<TransitionRecord> history = new ArrayList<>();

    public ReviewCase(String caseId, String transactionId, String accountId,
                      int scoreSnapshot, String rulesVersion, Instant openedAt) {
        this.caseId = caseId;
        this.transactionId = transactionId;
        this.accountId = accountId;
        this.scoreSnapshot = scoreSnapshot;
        this.rulesVersion = rulesVersion;
        this.openedAt = openedAt;
        this.status = CaseStatus.OPEN;
    }

    public String getCaseId() { return caseId; }
    public String getTransactionId() { return transactionId; }
    public String getAccountId() { return accountId; }
    public int getScoreSnapshot() { return scoreSnapshot; }
    public String getRulesVersion() { return rulesVersion; }
    public Instant getOpenedAt() { return openedAt; }
    public CaseStatus getStatus() { return status; }
    public void setStatus(CaseStatus status) { this.status = status; }
    public String getAssignee() { return assignee; }
    public void setAssignee(String assignee) { this.assignee = assignee; }
    public String getCloseReason() { return closeReason; }
    public void setCloseReason(String closeReason) { this.closeReason = closeReason; }
    public List<TransitionRecord> getHistory() { return history; }
}
