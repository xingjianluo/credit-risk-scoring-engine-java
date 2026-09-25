package com.github.xingjianluo.creditriskscoringenginejava.web;

import com.github.xingjianluo.creditriskscoringenginejava.model.RuleVersion;
import com.github.xingjianluo.creditriskscoringenginejava.version.RuleVersionRegistry;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 规则版本管理入口：查询、发布、激活（切换当前版本）。
 */
@RestController
@RequestMapping("/api/rules")
public class RuleAdminController {

    private final RuleVersionRegistry registry;

    public RuleAdminController(RuleVersionRegistry registry) {
        this.registry = registry;
    }

    @GetMapping("/versions")
    public List<RuleVersion> versions() {
        return registry.all();
    }

    @GetMapping("/versions/current")
    public RuleVersion current() {
        return registry.current();
    }

    @PostMapping("/versions")
    public RuleVersion publish(@RequestBody RuleVersion version) {
        return registry.publish(version);
    }

    @PostMapping("/versions/{version}/activate")
    public RuleVersion activate(@PathVariable String version) {
        return registry.activate(version);
    }
}
