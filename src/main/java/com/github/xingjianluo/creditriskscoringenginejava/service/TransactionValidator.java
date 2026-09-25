package com.github.xingjianluo.creditriskscoringenginejava.service;

import com.github.xingjianluo.creditriskscoringenginejava.domain.RejectReason;
import com.github.xingjianluo.creditriskscoringenginejava.domain.TransactionEvent;
import com.github.xingjianluo.creditriskscoringenginejava.domain.TransactionRequest;
import com.github.xingjianluo.creditriskscoringenginejava.domain.ValidationOutcome;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 流水字段校验。
 *
 * <p>任何异常都给出可区分的 {@link RejectReason} 并落账为 {@code RejectedSubmission}，
 * 绝不静默丢弃。校验顺序固定（主键 → 账户 → 金额 → 时间），保证同一输入拒绝原因稳定。
 */
@Component
public class TransactionValidator {

    public ValidationOutcome validate(TransactionRequest request, Instant now) {
        if (request == null) {
            return ValidationOutcome.reject(RejectReason.MISSING_TRANSACTION_ID,
                    "请求体为空");
        }
        String txnId = normalize(request.transactionId());
        if (txnId == null) {
            return ValidationOutcome.reject(RejectReason.MISSING_TRANSACTION_ID,
                    "transactionId 缺失或为空白");
        }
        String accountId = normalize(request.accountId());
        if (accountId == null) {
            return ValidationOutcome.reject(RejectReason.MISSING_ACCOUNT_ID,
                    "transactionId=" + txnId + " 的 accountId 缺失或为空白");
        }
        BigDecimal amount = request.amount();
        if (amount == null) {
            return ValidationOutcome.reject(RejectReason.MISSING_AMOUNT,
                    "transactionId=" + txnId + " 的 amount 缺失");
        }
        if (amount.signum() <= 0) {
            return ValidationOutcome.reject(RejectReason.NON_POSITIVE_AMOUNT,
                    "transactionId=" + txnId + " 的 amount=" + amount.toPlainString()
                            + " 非法，必须大于 0");
        }
        Instant eventTime = request.eventTime();
        if (eventTime == null) {
            return ValidationOutcome.reject(RejectReason.MISSING_EVENT_TIME,
                    "transactionId=" + txnId + " 的 eventTime 缺失");
        }
        if (eventTime.isAfter(now)) {
            return ValidationOutcome.reject(RejectReason.FUTURE_EVENT_TIME,
                    "transactionId=" + txnId + " 的 eventTime=" + eventTime
                            + " 晚于系统当前时间 " + now);
        }

        String payeeId = normalize(request.payeeId());
        String jurisdiction = request.jurisdiction() == null
                ? "" : request.jurisdiction().trim().toUpperCase();
        return ValidationOutcome.ok(new TransactionEvent(
                txnId, accountId, amount, payeeId, jurisdiction, eventTime));
    }

    private static String normalize(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        return s.trim();
    }
}
