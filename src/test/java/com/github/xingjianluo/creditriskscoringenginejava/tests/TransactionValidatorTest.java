package com.github.xingjianluo.creditriskscoringenginejava.tests;

import com.github.xingjianluo.creditriskscoringenginejava.model.RejectionReason;
import com.github.xingjianluo.creditriskscoringenginejava.model.RejectionRecord;
import com.github.xingjianluo.creditriskscoringenginejava.model.Transaction;
import com.github.xingjianluo.creditriskscoringenginejava.validation.TransactionValidator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TransactionValidatorTest {

    private static final Logger log = LoggerFactory.getLogger(TransactionValidatorTest.class);

    private final TransactionValidator validator =
            new TransactionValidator(Clock.fixed(Transactions.BASE,
                    java.time.ZoneOffset.UTC));

    @Test
    @DisplayName("不同字段问题返回可区分的拒绝原因码，且非法流水不静默丢弃")
    void rejectionReasonsAreDistinguishable() {
        List<TestInput> inputs = List.of(
                new TestInput(Transactions.builder("P1", "A1").amount((BigDecimal) null).build(),
                        RejectionReason.MISSING_AMOUNT),
                new TestInput(Transactions.builder("P2", "A1").amount("0").build(),
                        RejectionReason.NON_POSITIVE_AMOUNT),
                new TestInput(Transactions.builder("P3", "A1").amount("-1").build(),
                        RejectionReason.NON_POSITIVE_AMOUNT),
                new TestInput(Transactions.builder("P4", "A1").currency(null).build(),
                        RejectionReason.MISSING_CURRENCY),
                new TestInput(Transactions.builder("P5", "A1").currency("JPY").build(),
                        RejectionReason.UNSUPPORTED_CURRENCY),
                new TestInput(Transactions.builder("P6", "A1").time("2099-01-01T00:00:00Z").build(),
                        RejectionReason.FUTURE_EVENT_TIME),
                new TestInput(Transactions.builder("P7", "A1").cp(null).build(),
                        RejectionReason.MISSING_COUNTERPARTY),
                new TestInput(new Transaction("P8", null, new BigDecimal("1"), "CNY",
                        Transactions.BASE, "CP", "CN", "WEB"), RejectionReason.MISSING_ACCOUNT_ID),
                new TestInput(new Transaction(null, "A1", new BigDecimal("1"), "CNY",
                        Transactions.BASE, "CP", "CN", "WEB"), RejectionReason.MISSING_TRANSACTION_ID)
        );

        for (TestInput in : inputs) {
            RejectionRecord r = validator.validate(in.tx);
            log.info("[UT-REJECT] 输入信号(tx={},account={},amount={},ccy={},time={},cp={}) -> 命中拒绝码={} 判定依据={}",
                    in.tx.transactionId(), in.tx.accountId(), in.tx.amount(), in.tx.currency(),
                    in.tx.eventTime(), in.tx.counterpartyAccount(),
                    r == null ? "无" : r.reason(), r == null ? "通过" : r.detail());
            assertThat(r).as("应当被拒绝: %s", in.expected).isNotNull();
            assertThat(r.reason()).isEqualTo(in.expected);
            assertThat(r.fingerprint()).isNotBlank();
        }

        // 全部拒绝原因码互不相同（可区分）
        assertThat(inputs.stream().map(i -> i.expected).distinct())
                .as("用例覆盖的原因码应彼此可区分")
                .hasSizeGreaterThanOrEqualTo(7);
    }

    @Test
    @DisplayName("合法流水通过校验")
    void validTransactionPasses() {
        Transaction tx = Transactions.builder("OK1", "A1").build();
        log.info("[UT-VALID] 输入信号(tx={},amount={},time={},cp={}) -> 判定依据=校验通过",
                tx.transactionId(), tx.amount(), tx.eventTime(), tx.counterpartyAccount());
        assertThat(validator.validate(tx)).isNull();
    }

    private record TestInput(Transaction tx, RejectionReason expected) {
    }
}
