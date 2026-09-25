package com.github.xingjianluo.creditriskscoringenginejava.service;

import com.github.xingjianluo.creditriskscoringenginejava.domain.ReviewError;
import com.github.xingjianluo.creditriskscoringenginejava.domain.RuleSet;
import com.github.xingjianluo.creditriskscoringenginejava.store.RuleStore;
import com.github.xingjianluo.creditriskscoringenginejava.web.ApiException;
import com.github.xingjianluo.creditriskscoringenginejava.web.RuleDefinitionRequest;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;

/**
 * 规则版本管理。
 *
 * <p>激活新版本时，请求中缺省（null）的字段继承当前版本取值，因此小步调参无需重复全量字段；
 * 新版本通过 {@link RuleStore#activate} 校验后变为当前版本，旧版本保持只读。
 * 规则更新只影响之后到达的新流水；历史 {@code ScoreResult} 仍引用旧版本并可复现。
 */
@Service
public class RuleVersionService {

    private final RuleStore ruleStore;

    public RuleVersionService(RuleStore ruleStore) {
        this.ruleStore = ruleStore;
    }

    public RuleSet current() {
        return ruleStore.current();
    }

    public List<RuleSet> versions() {
        return ruleStore.versions();
    }

    public RuleSet get(String version) {
        return ruleStore.requireVersion(version);
    }

    public RuleSet activate(RuleDefinitionRequest req) {
        if (req == null || req.version() == null || req.version().isBlank()) {
            throw new ApiException(ReviewError.INVALID_RULE_DEFINITION, "version 不能为空");
        }
        RuleSet c = ruleStore.current();
        RuleSet candidate = new RuleSet(
                req.version().trim(),
                Instant.now(),
                req.amountLargeThreshold() != null ? req.amountLargeThreshold() : c.amountLargeThreshold(),
                req.amountLargePoints() != null ? req.amountLargePoints() : c.amountLargePoints(),
                req.rapidWindowSeconds() != null ? req.rapidWindowSeconds() : c.rapidWindowSeconds(),
                req.rapidSuccessionPoints() != null ? req.rapidSuccessionPoints() : c.rapidSuccessionPoints(),
                req.newPayeePoints() != null ? req.newPayeePoints() : c.newPayeePoints(),
                req.highRiskJurisdictions() != null
                        ? new HashSet<>(req.highRiskJurisdictions().stream()
                            .map(s -> s == null ? "" : s.trim().toUpperCase()).toList())
                        : c.highRiskJurisdictions(),
                req.highRiskJurisdictionPoints() != null ? req.highRiskJurisdictionPoints() : c.highRiskJurisdictionPoints(),
                req.offHoursStartHour() != null ? req.offHoursStartHour() : c.offHoursStartHour(),
                req.offHoursEndHour() != null ? req.offHoursEndHour() : c.offHoursEndHour(),
                req.offHoursPoints() != null ? req.offHoursPoints() : c.offHoursPoints(),
                req.velocityWindowSeconds() != null ? req.velocityWindowSeconds() : c.velocityWindowSeconds(),
                req.velocityAmountThreshold() != null ? req.velocityAmountThreshold() : c.velocityAmountThreshold(),
                req.velocityPoints() != null ? req.velocityPoints() : c.velocityPoints(),
                req.mediumThreshold() != null ? req.mediumThreshold() : c.mediumThreshold(),
                req.highThreshold() != null ? req.highThreshold() : c.highThreshold(),
                req.autoCaseThreshold() != null ? req.autoCaseThreshold() : c.autoCaseThreshold()
        );
        return ruleStore.activate(candidate);
    }

    /** 兼容旧调用（骨架阶段保留）。 */
    public RuleSet activate(RuleSet candidate) {
        return ruleStore.activate(candidate);
    }
}
