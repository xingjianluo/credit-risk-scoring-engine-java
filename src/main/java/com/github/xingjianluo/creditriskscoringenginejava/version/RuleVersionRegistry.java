package com.github.xingjianluo.creditriskscoringenginejava.version;

import com.github.xingjianluo.creditriskscoringenginejava.exception.RuleVersionNotFoundException;
import com.github.xingjianluo.creditriskscoringenginejava.model.RuleParameter;
import com.github.xingjianluo.creditriskscoringenginejava.model.RuleVersion;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 规则版本注册中心：内置不可变版本，支持发布新版本、切换当前版本。
 *
 * <p>历史评分结论固定记录其评分版本号；旧版本定义永远保留可读，
 * 因此历史结论可按其版本复现回溯。新流水只按当前激活版本评分。
 */
@Component
public class RuleVersionRegistry {

    /** 规则参数 code 常量，规则实现与版本定义共用。 */
    public static final String LARGE_AMOUNT = "LARGE_AMOUNT";
    public static final String NEW_COUNTERPARTY = "NEW_COUNTERPARTY";
    public static final String HIGH_VELOCITY = "HIGH_VELOCITY";
    public static final String OFFSHORE = "OFFSHORE";
    public static final String NIGHT_TIME = "NIGHT_TIME";
    public static final String UNUSUAL_AMOUNT = "UNUSUAL_AMOUNT";

    private final Map<String, RuleVersion> versions = new LinkedHashMap<>();
    private final AtomicReference<String> currentVersion = new AtomicReference<>();

    public RuleVersionRegistry() {
        Instant v1Time = Instant.parse("2026-01-01T00:00:00Z");
        publish(new RuleVersion("v1", v1Time, 60, 85, defaultV1Parameters(), List.of()));
        Instant v2Time = Instant.parse("2026-06-01T00:00:00Z");
        publish(new RuleVersion("v2", v2Time, 50, 75, defaultV2Parameters(), List.of("v1")));
        currentVersion.set("v2");
    }

    private static List<RuleParameter> defaultV1Parameters() {
        List<RuleParameter> p = new ArrayList<>();
        p.add(new RuleParameter(LARGE_AMOUNT, "大额交易阈值", "10000"));
        p.add(new RuleParameter(NEW_COUNTERPARTY, "新交易对手加分", "15"));
        p.add(new RuleParameter(HIGH_VELOCITY, "10分钟窗口高频笔数", "3"));
        p.add(new RuleParameter(OFFSHORE, "离岸高风险地区加分", "20"));
        p.add(new RuleParameter(NIGHT_TIME, "夜间时段(0-5点)加分", "10"));
        p.add(new RuleParameter(UNUSUAL_AMOUNT, "金额偏离基线倍数", "5"));
        return p;
    }

    private static List<RuleParameter> defaultV2Parameters() {
        // v2：收紧大额阈值、提高夜间权重、高频窗口更短更敏感
        List<RuleParameter> p = new ArrayList<>();
        p.add(new RuleParameter(LARGE_AMOUNT, "大额交易阈值", "5000"));
        p.add(new RuleParameter(NEW_COUNTERPARTY, "新交易对手加分", "15"));
        p.add(new RuleParameter(HIGH_VELOCITY, "10分钟窗口高频笔数", "2"));
        p.add(new RuleParameter(OFFSHORE, "离岸高风险地区加分", "25"));
        p.add(new RuleParameter(NIGHT_TIME, "夜间时段(0-5点)加分", "15"));
        p.add(new RuleParameter(UNUSUAL_AMOUNT, "金额偏离基线倍数", "3"));
        return p;
    }

    public RuleVersion current() {
        return get(currentVersion.get());
    }

    public RuleVersion get(String version) {
        RuleVersion v = versions.get(version);
        if (v == null) {
            throw new RuleVersionNotFoundException(version);
        }
        return v;
    }

    public List<RuleVersion> all() {
        return versions.values().stream()
                .sorted(Comparator.comparing(RuleVersion::createdAt))
                .toList();
    }

    /** 发布（登记）一个不可变新版本；重复版本号将被拒绝。 */
    public synchronized RuleVersion publish(RuleVersion version) {
        if (versions.containsKey(version.version())) {
            throw new IllegalArgumentException("规则版本已存在，不可覆盖: " + version.version());
        }
        versions.put(version.version(), version);
        return version;
    }

    /** 切换当前评分版本。 */
    public RuleVersion activate(String version) {
        get(version); // 不存在则抛 RuleVersionNotFoundException
        currentVersion.set(version);
        return get(version);
    }
}
