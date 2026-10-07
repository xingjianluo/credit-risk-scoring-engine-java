package com.github.xingjianluo.creditriskscoringenginejava.web;

import com.github.xingjianluo.creditriskscoringenginejava.caseflow.ReviewCaseService;
import com.github.xingjianluo.creditriskscoringenginejava.model.BatchDispositionRequest;
import com.github.xingjianluo.creditriskscoringenginejava.model.BatchDispositionResult;
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

    /** 批量处置：all-or-nothing；同一 batchId 重复提交返回首次结果。 */
    @PostMapping("/dispositions/batch")
    public BatchDispositionResult applyBatch(@RequestBody BatchDispositionRequest request) {
        return caseService.applyBatch(request);
    }

    @GetMapping("/{caseId}/events")
    public List<?> events(@PathVariable String caseId) {
        return caseService.events(caseId);
    }
}
