package com.github.xingjianluo.creditriskscoringenginejava.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * 统一时钟：生产用系统 UTC 时钟；测试可替换为固定时钟以保证时间相关规则可复现。
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
