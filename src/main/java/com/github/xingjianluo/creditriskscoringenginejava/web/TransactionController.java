package com.github.xingjianluo.creditriskscoringenginejava.web;

import com.github.xingjianluo.creditriskscoringenginejava.domain.RejectedSubmission;
import com.github.xingjianluo.creditriskscoringenginejava.domain.ScoreResult;
import com.github.xingjianluo.creditriskscoringenginejava.domain.SubmissionOutcome;
import com.github.xingjianluo.creditriskscoringenginejava.domain.TransactionRequest;
import com.github.xingjianluo.creditriskscoringenginejava.service.ScoringService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/transactions")
public class TransactionController {

    private final ScoringService scoringService;

    public TransactionController(ScoringService scoringService) {
        this.scoringService = scoringService;
    }

    @PostMapping
    public SubmissionOutcome submit(@RequestBody TransactionRequest request) {
        return scoringService.submit(request);
    }

    @GetMapping("/scores")
    public List<ScoreResult> scores() {
        return scoringService.scores();
    }

    @GetMapping("/rejections")
    public List<RejectedSubmission> rejections() {
        return scoringService.rejections();
    }

    @GetMapping("/{id}/score")
    public ScoreResult score(@PathVariable String id) {
        return scoringService.score(id);
    }

    @GetMapping("/{id}/replay")
    public Object replay(@PathVariable String id) {
        return scoringService.replay(id);
    }
}
