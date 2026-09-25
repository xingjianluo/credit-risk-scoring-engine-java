package com.github.xingjianluo.creditriskscoringenginejava.tests;

import com.github.xingjianluo.creditriskscoringenginejava.domain.ScenarioReport;
import com.github.xingjianluo.creditriskscoringenginejava.service.EventSimulator;
import com.github.xingjianluo.creditriskscoringenginejava.service.ScoringService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 端到端场景测试：本地事件模拟器的重复、乱序、并发、版本切换四类场景
 * 必须报告 consistent=true。
 */
@SpringBootTest
class EventSimulatorScenarioTest {

    private static final Logger log = LoggerFactory.getLogger(EventSimulatorScenarioTest.class);

    @Autowired
    private EventSimulator eventSimulator;
    @Autowired
    private ScoringService scoringService;

    @BeforeEach
    void reset() {
        scoringService.resetAll();
    }

    private void assertConsistent(ScenarioReport report) {
        report.outcomes().forEach(o -> log.info("[场景={}] {}", report.scenario(), o));
        assertTrue(report.consistent(),
                "场景 " + report.scenario() + " 不一致: " + report.outcomes());
    }

    @Test
    void duplicateScenario() {
        assertConsistent(eventSimulator.runDuplicates());
    }

    @Test
    void outOfOrderScenario() {
        assertConsistent(eventSimulator.runOutOfOrder());
    }

    @Test
    void concurrentScenario() throws InterruptedException {
        assertConsistent(eventSimulator.runConcurrent(12));
    }

    @Test
    void versionSwitchScenario() {
        assertConsistent(eventSimulator.runVersionSwitch());
        // 场景结束后规则已切到 v2，重置后应恢复 v1（供后续场景使用）
        scoringService.resetAll();
        assertEquals("v1", eventSimulator.runDuplicates() != null
                ? scoringService.scores().get(0).rulesVersion() : null);
    }
}
