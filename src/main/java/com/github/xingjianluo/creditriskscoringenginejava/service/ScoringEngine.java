package com.github.xingjianluo.creditriskscoringenginejava.service;

import com.github.xingjianluo.creditriskscoringenginejava.domain.FactorCode;
import com.github.xingjianluo.creditriskscoringenginejava.domain.FactorContribution;
import com.github.xingjianluo.creditriskscoringenginejava.domain.RiskLevel;
import com.github.xingjianluo.creditriskscoringenginejava.domain.RuleSet;
import com.github.xingjianluo.creditriskscoringenginejava.domain.ScoreResult;
import com.github.xingjianluo.creditriskscoringenginejava.domain.TransactionEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * 多因子规则评分引擎。
 *
 * <p>每个因子独立判定，命中才贡献一个非负固定分值，因此：
 * <ul>
 *   <li>总分 = Σ 因子贡献，对任何风险因素单调（风险因素增多/增强不会降分）；</li>
 *   <li>每个因子都附带可读 evidence（输入信号 + 阈值 + 命中结果），结论可解释；</li>
 *   <li>只依赖规则版本快照与"事件时间更早的历史流水"，与提交顺序/到达时刻无关。</li>
 * </ul>
 */
@Component
public class ScoringEngine {

    private static final Logger log = LoggerFactory.getLogger(ScoringEngine.class);

    public ScoreResult score(TransactionEvent event, List<TransactionEvent> prior,
                             RuleSet rules, Instant scoredAt) {
        List<FactorContribution> contributions = new ArrayList<>();

        addAmountLarge(contributions, event, rules);
        addRapidSuccession(contributions, event, prior, rules);
        addNewPayee(contributions, event, prior, rules);
        addHighRiskJurisdiction(contributions, event, rules);
        addOffHours(contributions, event, rules);
        addVelocity(contributions, event, prior, rules);

        int total = contributions.stream().mapToInt(FactorContribution::points).sum();
        RiskLevel level = rules.levelOf(total);

        log.info("[评分输入] txnId={} account={} amount={} payee={} jurisdiction={} eventTime={} "
                        + "version={} 历史基线笔数={}",
                event.transactionId(), event.accountId(), event.amount().toPlainString(),
                event.payeeId() == null ? "<缺失>" : event.payeeId(),
                event.jurisdiction(), event.eventTime(), rules.version(), prior.size());
        if (contributions.isEmpty()) {
            log.info("[命中规则] txnId={} 无因子命中，总分=0", event.transactionId());
        }
        contributions.forEach(c -> log.info("[命中规则] txnId={} factor={} points={} 依据={}",
                event.transactionId(), c.factor(), c.points(), c.evidence()));
        log.info("[判定依据] txnId={} version={} 总分={} 等级={} (medium>={}, high>={})",
                event.transactionId(), rules.version(), total, level,
                rules.mediumThreshold(), rules.highThreshold());

        return new ScoreResult(event.transactionId(), event.accountId(), total, level,
                List.copyOf(contributions), rules.version(), scoredAt,
                prior.stream().map(TransactionEvent::transactionId).toList());
    }

    private void addAmountLarge(List<FactorContribution> out, TransactionEvent e, RuleSet r) {
        if (e.amount().compareTo(r.amountLargeThreshold()) >= 0) {
            out.add(new FactorContribution(FactorCode.AMOUNT_LARGE, r.amountLargePoints(),
                    "金额 " + e.amount().toPlainString() + " >= 大额阈值 "
                            + r.amountLargeThreshold().toPlainString()));
        }
    }

    private void addRapidSuccession(List<FactorContribution> out, TransactionEvent e,
                                    List<TransactionEvent> prior, RuleSet r) {
        if (prior.isEmpty()) {
            return;
        }
        TransactionEvent last = prior.get(prior.size() - 1);
        long gap = Duration.between(last.eventTime(), e.eventTime()).getSeconds();
        if (gap >= 0 && gap <= r.rapidWindowSeconds()) {
            out.add(new FactorContribution(FactorCode.RAPID_SUCCESSION,
                    r.rapidSuccessionPoints(),
                    "距上一笔 " + last.transactionId() + " 仅 " + gap + " 秒，落在 "
                            + r.rapidWindowSeconds() + " 秒连续窗口内"));
        }
    }

    private void addNewPayee(List<FactorContribution> out, TransactionEvent e,
                             List<TransactionEvent> prior, RuleSet r) {
        String payee = e.payeeId();
        if (payee == null) {
            return; // 收款方缺失不作为命中因子（字段校验阶段可补充规则，当前不臆断）
        }
        boolean seen = prior.stream().anyMatch(p -> payee.equals(p.payeeId()));
        if (!seen) {
            out.add(new FactorContribution(FactorCode.NEW_PAYEE, r.newPayeePoints(),
                    "收款方 " + payee + " 未出现在该账户更早的 "
                            + prior.size() + " 笔历史流水中"));
        }
    }

    private void addHighRiskJurisdiction(List<FactorContribution> out,
                                         TransactionEvent e, RuleSet r) {
        if (r.highRiskJurisdictions().contains(e.jurisdiction())) {
            out.add(new FactorContribution(FactorCode.HIGH_RISK_JURISDICTION,
                    r.highRiskJurisdictionPoints(),
                    "司法辖区 " + e.jurisdiction() + " 在高风险名单 "
                            + r.highRiskJurisdictions() + " 中"));
        }
    }

    private void addOffHours(List<FactorContribution> out, TransactionEvent e, RuleSet r) {
        int hour = e.eventTime().atZone(ZoneOffset.UTC).getHour();
        int start = r.offHoursStartHour();
        int end = r.offHoursEndHour();
        boolean hit = start < end ? (hour >= start && hour < end)
                                  : (hour >= start || hour < end);
        if (hit) {
            out.add(new FactorContribution(FactorCode.OFF_HOURS, r.offHoursPoints(),
                    "交易 UTC 小时 " + hour + " 落在深夜窗口 [" + start + "," + end + ")"));
        }
    }

    private void addVelocity(List<FactorContribution> out, TransactionEvent e,
                             List<TransactionEvent> prior, RuleSet r) {
        Instant from = e.eventTime().minusSeconds(r.velocityWindowSeconds());
        BigDecimal windowSum = BigDecimal.ZERO;
        int count = 0;
        for (TransactionEvent p : prior) {
            if (!p.eventTime().isBefore(from)) {
                windowSum = windowSum.add(p.amount());
                count++;
            }
        }
        if (windowSum.compareTo(r.velocityAmountThreshold()) >= 0) {
            out.add(new FactorContribution(FactorCode.VELOCITY_AMOUNT, r.velocityPoints(),
                    "前 " + r.velocityWindowSeconds() + " 秒内已有 " + count
                            + " 笔，历史累计金额 " + windowSum.toPlainString()
                            + " >= 阈值 " + r.velocityAmountThreshold().toPlainString()));
        }
    }
}
