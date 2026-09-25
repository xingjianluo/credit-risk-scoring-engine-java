package com.github.xingjianluo.creditriskscoringenginejava.web;

import com.github.xingjianluo.creditriskscoringenginejava.domain.ReviewCase;
import com.github.xingjianluo.creditriskscoringenginejava.service.CaseService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/cases")
public class CaseController {

    private final CaseService caseService;

    public CaseController(CaseService caseService) {
        this.caseService = caseService;
    }

    @GetMapping
    public List<ReviewCase> list() {
        return caseService.list();
    }

    @GetMapping("/{caseId}")
    public ReviewCase get(@PathVariable String caseId) {
        return caseService.get(caseId);
    }

    @PostMapping("/{caseId}/transitions")
    public ReviewCase transition(@PathVariable String caseId, @RequestBody CaseActionRequest request) {
        return caseService.transition(caseId, request.action(), request.role(), request.actor(), request.reason());
    }
}
