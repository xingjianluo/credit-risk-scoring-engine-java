package com.github.xingjianluo.creditriskscoringenginejava.web;

import com.github.xingjianluo.creditriskscoringenginejava.domain.RuleSet;
import com.github.xingjianluo.creditriskscoringenginejava.service.RuleVersionService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/rules")
public class RuleController {

    private final RuleVersionService ruleVersionService;

    public RuleController(RuleVersionService ruleVersionService) {
        this.ruleVersionService = ruleVersionService;
    }

    @GetMapping("/current")
    public RuleSet current() {
        return ruleVersionService.current();
    }

    @GetMapping
    public List<RuleSet> versions() {
        return ruleVersionService.versions();
    }

    @GetMapping("/{version}")
    public RuleSet get(@PathVariable String version) {
        return ruleVersionService.get(version);
    }

    @PostMapping("/activate")
    public RuleSet activate(@RequestBody RuleDefinitionRequest request) {
        return ruleVersionService.activate(request);
    }
}
