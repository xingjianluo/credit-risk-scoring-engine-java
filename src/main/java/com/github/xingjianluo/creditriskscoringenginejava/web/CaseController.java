package com.github.xingjianluo.creditriskscoringenginejava.web;

import com.github.xingjianluo.creditriskscoringenginejava.caseflow.ReviewCaseService;
import com.github.xingjianluo.creditriskscoringenginejava.model.DispositionRequest;
import com.github.xingjianluo.creditriskscoringenginejava.model.ReviewCase;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 审查案件查询与处置入口。
 */
@RestController
@RequestMapping("/api/cases")
public class CaseController {

    private final ReviewCaseService caseService;

    public CaseController(ReviewCaseService caseService) {
        this.caseService = caseService;
    }

    @GetMapping
    public List<ReviewCase> all() {
        return caseService.all();
    }

    @GetMapping("/{caseId}")
    public ReviewCase get(@PathVariable String caseId) {
        return caseService.get(caseId);
    }

    @PostMapping("/dispositions")
    public ReviewCase apply(@RequestBody DispositionRequest request) {
        return caseService.apply(request);
    }

    @GetMapping("/{caseId}/events")
    public List<?> events(@PathVariable String caseId) {
        return caseService.events(caseId);
    }
}
