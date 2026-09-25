package com.github.xingjianluo.creditriskscoringenginejava.store;

import com.github.xingjianluo.creditriskscoringenginejava.domain.ReviewError;
import com.github.xingjianluo.creditriskscoringenginejava.domain.RuleSet;
import com.github.xingjianluo.creditriskscoringenginejava.web.ApiException;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 规则版本仓库（内存，线程安全）。
 *
 * <p>兼容规则：新版本激活只替换"当前版本"指针，旧版本对象永不修改且永久可读；
 * 已产生的 {@code ScoreResult} 持有自己的 version，回溯时按版本号取回即可逐字段复现。
 */
@Component
public class RuleStore {

    private final ConcurrentMap<String, RuleSet> byVersion = new ConcurrentHashMap<>();
    private final AtomicReference<RuleSet> current = new AtomicReference<>();

    public RuleStore() {
        resetToV1();
    }

    /** 恢复为仅含内置 v1 的初始状态（供本地场景/测试隔离）。 */
    public synchronized void resetToV1() {
        byVersion.clear();
        RuleSet v1 = RuleSet.v1(Instant.now());
        byVersion.put(v1.version(), v1);
        current.set(v1);
    }

    public RuleSet current() {
        return current.get();
    }

    public Optional<RuleSet> findByVersion(String version) {
        return Optional.ofNullable(byVersion.get(version));
    }

    public RuleSet requireVersion(String version) {
        return findByVersion(version).orElseThrow(() -> new ApiException(
                ReviewError.RULE_VERSION_NOT_FOUND, "version=" + version));
    }

    /**
     * 激活新版本：版本号不得与既有版本重复（旧版本不可覆盖）。
     */
    public synchronized RuleSet activate(RuleSet candidate) {
        if (candidate == null) {
            throw new ApiException(ReviewError.INVALID_RULE_DEFINITION, "规则为空");
        }
        List<String> errors = candidate.validate();
        if (!errors.isEmpty()) {
            throw new ApiException(ReviewError.INVALID_RULE_DEFINITION,
                    String.join("; ", errors));
        }
        if (byVersion.putIfAbsent(candidate.version(), candidate) != null) {
            throw new ApiException(ReviewError.INVALID_RULE_DEFINITION,
                    "版本号已存在且旧版本不可覆盖: " + candidate.version());
        }
        current.set(candidate);
        return candidate;
    }

    /**
     * 按激活时间升序返回全部版本。
     */
    public List<RuleSet> versions() {
        List<RuleSet> all = new ArrayList<>(byVersion.values());
        all.sort(Comparator.comparing(RuleSet::activatedAt));
        return all;
    }
}
