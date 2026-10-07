package com.github.xingjianluo.creditriskscoringenginejava.web;

import com.github.xingjianluo.creditriskscoringenginejava.caseflow.BatchDispositionService;
import com.github.xingjianluo.creditriskscoringenginejava.caseflow.ReviewCaseService;
import com.github.xingjianluo.creditriskscoringenginejava.model.BatchDispositionReport;
import com.github.xingjianluo.creditriskscoringenginejava.model.BatchDispositionRequest;
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
    private final BatchDispositionService batchDispositionService;

    public CaseController(ReviewCaseService caseService,
                          BatchDispositionService batchDispositionService) {
        this.caseService = caseService;
        this.batchDispositionService = batchDispositionService;
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
    public ReviewCase apply(@RequestBody(required = false) DispositionRequest request) {
        return caseService.apply(request);
    }

    /**
     * 批量处置：对同一账户下的一批案件统一应用一个动作。
     * 整批原子（任一不满足则整批拒绝并逐条说明原因），batchId 幂等。
     */
    @PostMapping("/dispositions/batch")
    public BatchDispositionReport applyBatch(@RequestBody(required = false) BatchDispositionRequest request) {
        return batchDispositionService.applyBatch(request);
    }

    @GetMapping("/{caseId}/events")
    public List<?> events(@PathVariable String caseId) {
        return caseService.events(caseId);
    }
}
