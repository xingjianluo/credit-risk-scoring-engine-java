package com.github.xingjianluo.creditriskscoringenginejava.domain;

/**
 * 流水被拒绝进入评分流程的可区分原因。
 */
public enum RejectReason {
    MISSING_TRANSACTION_ID("流水号缺失"),
    MISSING_ACCOUNT_ID("账户号缺失"),
    MISSING_AMOUNT("交易金额缺失"),
    NON_POSITIVE_AMOUNT("交易金额非法：必须大于 0"),
    MISSING_EVENT_TIME("交易时间缺失"),
    FUTURE_EVENT_TIME("交易时间异常：晚于系统当前时间");

    private final String description;

    RejectReason(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}
