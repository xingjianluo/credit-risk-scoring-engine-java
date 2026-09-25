package com.github.xingjianluo.creditriskscoringenginejava.config;

import com.github.xingjianluo.creditriskscoringenginejava.model.ArrivalMode;
import com.github.xingjianluo.creditriskscoringenginejava.model.BatchReport;
import com.github.xingjianluo.creditriskscoringenginejava.model.Transaction;
import com.github.xingjianluo.creditriskscoringenginejava.service.LocalEventSimulator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * 本地演示数据播种：app.demo.seed=true 时在启动后用内存事件流
 * 依次模拟顺序/乱序/并发三种到达方式并打印一致性对比。
 */
@Component
public class DataSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);

    private final LocalEventSimulator simulator;

    @Value("${app.demo.seed:false}")
    private boolean seed;

    private final Clock clock;

    public DataSeeder(LocalEventSimulator simulator, Clock clock) {
        this.simulator = simulator;
        this.clock = clock;
    }

    @Override
    public void run(String... args) {
        if (!seed) {
            return;
        }
        List<Transaction> events = sampleEvents();
        BatchReport ordered = simulator.run("demo-ordered", events, ArrivalMode.IN_ORDER);
        BatchReport shuffled = simulator.run("demo-shuffled", events, ArrivalMode.OUT_OF_ORDER);
        BatchReport concurrent = simulator.run("demo-concurrent", events, ArrivalMode.CONCURRENT);
        log.info("[DEMO] 顺序: 接受={} 拒绝={} 重复={} 开案={}",
                ordered.accepted(), ordered.rejected(), ordered.duplicates(), ordered.casesOpened());
        log.info("[DEMO] 乱序: 接受={} 拒绝={} 重复={} 开案={}",
                shuffled.accepted(), shuffled.rejected(), shuffled.duplicates(), shuffled.casesOpened());
        log.info("[DEMO] 并发: 接受={} 拒绝={} 重复={} 开案={}",
                concurrent.accepted(), concurrent.rejected(), concurrent.duplicates(), concurrent.casesOpened());
        boolean same = ordered.accepted() == shuffled.accepted()
                && ordered.accepted() == concurrent.accepted()
                && ordered.rejected() == shuffled.rejected()
                && ordered.rejected() == concurrent.rejected()
                && ordered.casesOpened() == shuffled.casesOpened()
                && ordered.casesOpened() == concurrent.casesOpened();
        log.info("[DEMO] 三种到达方式计数一致: {}", same);
    }

    /**
     * 构造含正常流水、各风险因子、以及多种非法流水（含重复 ID）的样本。
     * 时间取相对当前时钟的近期时刻（夜间 02 点附近），保证任何机器时钟下都可运行。
     */
    public List<Transaction> sampleEvents() {
        Instant now = Instant.now(clock);
        Instant night = now.atOffset(ZoneOffset.UTC)
                .withHour(2).withMinute(30).withSecond(0).withNano(0)
                .toInstant();
        if (night.isAfter(now)) {
            night = night.minus(Duration.ofDays(1));
        }
        Instant t0 = night;
        List<Transaction> list = new ArrayList<>();
        list.add(new Transaction("T1001", "A001", new BigDecimal("800"), "CNY",
                t0.minusSeconds(600), "CP-9", "CN", "MOBILE"));
        list.add(new Transaction("T1002", "A001", new BigDecimal("900"), "CNY",
                t0.minusSeconds(300), "CP-9", "CN", "MOBILE"));
        list.add(new Transaction("T1003", "A001", new BigDecimal("12000"), "CNY",
                t0, "CP-7", "KY", "WEB"));
        list.add(new Transaction("T1004", "A001", new BigDecimal("950"), "CNY",
                t0.plusSeconds(60), "CP-9", "CN", "MOBILE"));
        list.add(new Transaction("T1005", "A001", new BigDecimal("600"), "CNY",
                t0.plusSeconds(120), "CP-2", "CN", "WEB"));
        // 重复 ID（模拟重复提交）
        list.add(new Transaction("T1003", "A001", new BigDecimal("12000"), "CNY",
                t0, "CP-7", "KY", "WEB"));
        // 非法流水：负金额 / 缺币种 / 未来时间 / 缺对手
        list.add(new Transaction("T9001", "A002", new BigDecimal("-50"), "CNY",
                t0, "CP-1", "CN", "WEB"));
        list.add(new Transaction("T9002", "A002", new BigDecimal("500"), null,
                t0, "CP-1", "CN", "WEB"));
        list.add(new Transaction("T9003", "A002", new BigDecimal("500"), "CNY",
                Instant.parse("2099-01-01T00:00:00Z"), "CP-1", "CN", "WEB"));
        list.add(new Transaction("T9004", "A002", new BigDecimal("500"), "CNY",
                t0, null, "CN", "WEB"));
        return list;
    }
}
