package com.github.xingjianluo.creditriskscoringenginejava.web;

import com.github.xingjianluo.creditriskscoringenginejava.domain.ScenarioReport;
import com.github.xingjianluo.creditriskscoringenginejava.service.EventSimulator;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 本地验证入口：一键运行重复/乱序/并发/版本切换场景。
 */
@RestController
@RequestMapping("/api/scenarios")
public class ScenarioController {

    private final EventSimulator eventSimulator;

    public ScenarioController(EventSimulator eventSimulator) {
        this.eventSimulator = eventSimulator;
    }

    @org.springframework.web.bind.annotation.PostMapping("/reset")
    public String reset() {
        eventSimulator.reset();
        return "reset";
    }

    @GetMapping("/duplicates")
    public ScenarioReport duplicates() {
        return eventSimulator.runDuplicates();
    }

    @GetMapping("/out-of-order")
    public ScenarioReport outOfOrder() {
        return eventSimulator.runOutOfOrder();
    }

    @GetMapping("/concurrent")
    public ScenarioReport concurrent(@RequestParam(defaultValue = "8") int threadsPerTransaction)
            throws InterruptedException {
        return eventSimulator.runConcurrent(threadsPerTransaction);
    }

    @GetMapping("/version-switch")
    public ScenarioReport versionSwitch() {
        return eventSimulator.runVersionSwitch();
    }
}
