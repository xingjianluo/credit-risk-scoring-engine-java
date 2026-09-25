package com.github.xingjianluo.creditriskscoringenginejava.validation;

import com.github.xingjianluo.creditriskscoringenginejava.model.RejectionRecord;
import com.github.xingjianluo.creditriskscoringenginejava.model.RejectionReason;
import com.github.xingjianluo.creditriskscoringenginejava.model.Transaction;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Set;

/**
 * 流水合法性校验：字段缺失 / 金额非法 / 时间异常 / 交易对手缺失。
 * 返回 null 表示通过；否则返回带可区分原因码的拒绝记录。
 *
 * <p>校验按固定字段顺序短路返回首个问题，因此不同的字段问题对应可区分的原因码。
 */
@Component
public class TransactionValidator {

    public static final Set<String> SUPPORTED_CURRENCIES = Set.of("CNY", "USD", "EUR", "HKD");

    /** 允许的未来时钟偏移：超过即判定时间异常。 */
    public static final Duration MAX_FUTURE_SKEW = Duration.ofMinutes(5);

    private final Clock clock;

    public TransactionValidator(Clock clock) {
        this.clock = clock;
    }

    public RejectionRecord validate(Transaction tx) {
        if (tx == null) {
            return reject(null, null, RejectionReason.MISSING_TRANSACTION_ID, "流水为空", null);
        }
        String txId = blankToNull(tx.transactionId());
        String accountId = blankToNull(tx.accountId());

        if (txId == null) {
            return reject(tx, accountId, RejectionReason.MISSING_TRANSACTION_ID, "transactionId 为空", txId);
        }
        if (accountId == null) {
            return reject(tx, null, RejectionReason.MISSING_ACCOUNT_ID, "accountId 为空", txId);
        }
        if (tx.amount() == null) {
            return reject(tx, accountId, RejectionReason.MISSING_AMOUNT, "amount 缺失", txId);
        }
        if (tx.amount().signum() <= 0) {
            return reject(tx, accountId, RejectionReason.NON_POSITIVE_AMOUNT,
                    "amount 必须大于 0，实际=" + tx.amount(), txId);
        }
        String currency = blankToNull(tx.currency());
        if (currency == null) {
            return reject(tx, accountId, RejectionReason.MISSING_CURRENCY, "currency 为空", txId);
        }
        if (!SUPPORTED_CURRENCIES.contains(currency)) {
            return reject(tx, accountId, RejectionReason.UNSUPPORTED_CURRENCY,
                    "currency 不受支持: " + currency, txId);
        }
        if (tx.eventTime() == null) {
            return reject(tx, accountId, RejectionReason.MISSING_EVENT_TIME, "eventTime 缺失", txId);
        }
        if (tx.eventTime().isAfter(Instant.now(clock).plus(MAX_FUTURE_SKEW))) {
            return reject(tx, accountId, RejectionReason.FUTURE_EVENT_TIME,
                    "eventTime 超出允许的未来偏移 " + MAX_FUTURE_SKEW + ": " + tx.eventTime(), txId);
        }
        if (blankToNull(tx.counterpartyAccount()) == null) {
            return reject(tx, accountId, RejectionReason.MISSING_COUNTERPARTY, "counterpartyAccount 为空", txId);
        }
        return null;
    }

    private RejectionRecord reject(Transaction tx, String accountId, RejectionReason reason,
                                   String detail, String txId) {
        String raw = String.valueOf(txId) + '|' + String.valueOf(accountId) + '|' + reason;
        String fingerprint = sha256(raw + '|' + (tx == null ? "" : String.valueOf(tx.amount()))
                + '|' + (tx == null ? "" : String.valueOf(tx.eventTime())));
        return new RejectionRecord(txId, accountId, reason, detail, Instant.now(clock), fingerprint);
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    private static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
