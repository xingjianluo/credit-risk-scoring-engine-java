package com.github.xingjianluo.creditriskscoringenginejava.web;

import com.github.xingjianluo.creditriskscoringenginejava.exception.InvalidRequestException;
import com.github.xingjianluo.creditriskscoringenginejava.ledger.TransactionLedger;
import com.github.xingjianluo.creditriskscoringenginejava.model.ArrivalMode;
import com.github.xingjianluo.creditriskscoringenginejava.model.BatchReport;
import com.github.xingjianluo.creditriskscoringenginejava.model.RejectionRecord;
import com.github.xingjianluo.creditriskscoringenginejava.model.RiskScoreResult;
import com.github.xingjianluo.creditriskscoringenginejava.model.SubmissionOutcome;
import com.github.xingjianluo.creditriskscoringenginejava.model.Transaction;
import com.github.xingjianluo.creditriskscoringenginejava.service.LocalEventSimulator;
import com.github.xingjianluo.creditriskscoringenginejava.service.RiskScoringService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 交易评分与本地模拟入口。
 */
@RestController
@RequestMapping("/api/risk")
public class RiskController {

    private final RiskScoringService scoringService;
    private final LocalEventSimulator simulator;
    private final TransactionLedger ledger;

    public RiskController(RiskScoringService scoringService, LocalEventSimulator simulator,
                          TransactionLedger ledger) {
        this.scoringService = scoringService;
        this.simulator = simulator;
        this.ledger = ledger;
    }

    @PostMapping("/transactions")
    public SubmissionOutcome submit(@RequestBody(required = false) Transaction tx) {
        return scoringService.submit(tx);
    }

    /**
     * 批量提交入口：与单笔/模拟回放共用同一套校验-评分管线，
     * 逐条返回结果（含可区分拒绝原因），异常输入不影响同批其它流水。
     */
    @PostMapping("/transactions/batch")
    public List<SubmissionOutcome> submitBatch(@RequestBody(required = false) List<Transaction> txs) {
        return scoringService.submitBatch(txs == null ? List.of() : txs);
    }

    /** 查询首次结论：评分成功 200；被拒绝 422（带可区分原因码）；不存在 404。 */
    @GetMapping("/transactions/{id}")
    public ResponseEntity<?> get(@PathVariable String id) {
        var score = ledger.scoreOf(id);
        if (score.isPresent()) {
            return ResponseEntity.ok(score.get());
        }
        var rejection = ledger.rejectionOf(id);
        if (rejection.isPresent()) {
            RejectionRecord r = rejection.get();
            return ResponseEntity.unprocessableEntity().body(Map.of(
                    "errorCode", r.reason().name(),
                    "reason", r.reason().defaultMessage(),
                    "detail", r.detail() == null ? "" : r.detail(),
                    "fingerprint", r.fingerprint() == null ? "" : r.fingerprint()));
        }
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("errorCode", "TRANSACTION_NOT_FOUND", "transactionId", id));
    }

    @GetMapping("/scores")
    public List<RiskScoreResult> scores() {
        return scoringService.allScores();
    }

    @GetMapping("/rejections")
    public List<RejectionRecord> rejections() {
        return ledger.allRejections();
    }

    /** 触发本地模拟：body 为 {"mode":"OUT_OF_ORDER","transactions":[...]}。 */
    @PostMapping("/simulate")
    public BatchReport simulate(@RequestBody(required = false) SimulationRequest body) {
        if (body == null) {
            throw new InvalidRequestException("MISSING_REQUEST_BODY", "模拟请求体缺失");
        }
        ArrivalMode mode = parseMode(body.mode());
        List<Transaction> txs = body.transactions() == null ? List.of() : body.transactions();
        return simulator.run(body.runId(), txs, mode);
    }

    /** 到达方式容错解析：非法值给出可区分 400，而不是系统级 500。 */
    private static ArrivalMode parseMode(String raw) {
        if (raw == null || raw.isBlank()) {
            return ArrivalMode.IN_ORDER;
        }
        try {
            return ArrivalMode.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new InvalidRequestException("INVALID_ARRIVAL_MODE",
                    "非法到达方式: " + raw + "，可选值: IN_ORDER/OUT_OF_ORDER/CONCURRENT");
        }
    }

    public record SimulationRequest(String runId, String mode, List<Transaction> transactions) {
    }
}
