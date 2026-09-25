package com.github.xingjianluo.creditriskscoringenginejava.baseline;

import com.github.xingjianluo.creditriskscoringenginejava.ledger.TransactionLedger;
import com.github.xingjianluo.creditriskscoringenginejava.model.BaselineSnapshot;
import com.github.xingjianluo.creditriskscoringenginejava.model.Transaction;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 账户历史基线服务。
 *
 * <p>基线<b>只取 eventTime 严格早于当前流水</b>的同账户已入账历史：
 * 一笔晚到的旧流水不会改变更早流水的基线，因为其 eventTime 更晚被排除；
 * 因而任何提交排列下，给定交易集合中的每笔流水基线都相同，结论与到达顺序无关。
 */
@Component
public class AccountBaselineService {

    /** 高频窗口：评分时刻往前 10 分钟。 */
    public static final Duration VELOCITY_WINDOW = Duration.ofMinutes(10);

    private final TransactionLedger ledger;

    public AccountBaselineService(TransactionLedger ledger) {
        this.ledger = ledger;
    }

    public BaselineSnapshot baselineFor(Transaction tx) {
        Instant asOf = tx.eventTime();
        // 排除自身（并发场景可能已入账），只看严格更早的历史
        var prior = ledger.transactionsOfAccount(tx.accountId()).stream()
                .filter(t -> !Objects.equals(t.transactionId(), tx.transactionId()))
                .filter(t -> t.eventTime() != null && t.eventTime().isBefore(asOf))
                .toList();

        int priorCount = prior.size();
        BigDecimal avg = prior.isEmpty()
                ? BigDecimal.ZERO
                : prior.stream().map(Transaction::amount).reduce(BigDecimal.ZERO, BigDecimal::add)
                        .divide(BigDecimal.valueOf(priorCount), 2, RoundingMode.HALF_UP);
        int recentWindowCount = (int) prior.stream()
                .filter(t -> !t.eventTime().isBefore(asOf.minus(VELOCITY_WINDOW)))
                .count();
        Set<String> counterparties = prior.stream()
                .map(Transaction::counterpartyAccount)
                .filter(Objects::nonNull)
                .collect(Collectors.toUnmodifiableSet());

        return new BaselineSnapshot(tx.accountId(), asOf, priorCount,
                avg.toPlainString(), recentWindowCount, counterparties);
    }
}
