package com.github.xingjianluo.creditriskscoringenginejava.web;

import java.math.BigDecimal;
import java.util.List;

/**
 * 新版本规则的激活请求（字段可缺省，缺省值复制自当前版本）。
 */
public record RuleDefinitionRequest(
        String version,
        BigDecimal amountLargeThreshold,
        Integer amountLargePoints,
        Long rapidWindowSeconds,
        Integer rapidSuccessionPoints,
        Integer newPayeePoints,
        List<String> highRiskJurisdictions,
        Integer highRiskJurisdictionPoints,
        Integer offHoursStartHour,
        Integer offHoursEndHour,
        Integer offHoursPoints,
        Long velocityWindowSeconds,
        BigDecimal velocityAmountThreshold,
        Integer velocityPoints,
        Integer mediumThreshold,
        Integer highThreshold,
        Integer autoCaseThreshold
) {
}
