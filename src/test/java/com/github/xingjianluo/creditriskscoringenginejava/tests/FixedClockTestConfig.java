package com.github.xingjianluo.creditriskscoringenginejava.tests;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Clock;
import java.time.ZoneOffset;

/**
 * 集成测试统一时钟：固定在样本时间之后，避免“未来时间”拒绝依赖真实机器时钟，
 * 保证测试可复现。
 */
@TestConfiguration
public class FixedClockTestConfig {

    @Bean
    @Primary
    public Clock fixedClock() {
        return Clock.fixed(java.time.Instant.parse("2026-09-26T23:00:00Z"), ZoneOffset.UTC);
    }
}
