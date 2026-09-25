package com.github.xingjianluo.creditriskscoringenginejava.domain;

/**
 * 风险评分因子（规则维度）。每个因子只能贡献非负分值，保证总分对风险因素单调。
 */
public enum FactorCode {
    AMOUNT_LARGE("单笔大额"),
    RAPID_SUCCESSION("短时连续交易"),
    NEW_PAYEE("陌生收款方"),
    HIGH_RISK_JURISDICTION("高风险司法辖区"),
    OFF_HOURS("深夜时段交易"),
    VELOCITY_AMOUNT("滚动窗口累计金额突增");

    private final String label;

    FactorCode(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
