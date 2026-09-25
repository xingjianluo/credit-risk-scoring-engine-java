package com.github.xingjianluo.creditriskscoringenginejava.model;

/**
 * 可区分的流水拒绝原因码。
 */
public enum RejectionReason {
    MISSING_TRANSACTION_ID("交易 ID 缺失"),
    MISSING_ACCOUNT_ID("账户 ID 缺失"),
    MISSING_AMOUNT("金额缺失"),
    NON_POSITIVE_AMOUNT("金额非法：金额必须大于 0"),
    MISSING_CURRENCY("币种缺失"),
    UNSUPPORTED_CURRENCY("币种不受支持"),
    MISSING_EVENT_TIME("交易时间缺失"),
    FUTURE_EVENT_TIME("时间异常：交易时间超出允许的未来时钟偏移"),
    MISSING_COUNTERPARTY("交易对手缺失");

    private final String defaultMessage;

    RejectionReason(String defaultMessage) {
        this.defaultMessage = defaultMessage;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}
