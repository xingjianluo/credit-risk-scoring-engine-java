package com.github.xingjianluo.creditriskscoringenginejava.web;

import com.github.xingjianluo.creditriskscoringenginejava.caseflow.AccountCaseService;
import com.github.xingjianluo.creditriskscoringenginejava.model.AccountCase;
import com.github.xingjianluo.creditriskscoringenginejava.model.CaseEvent;
import com.github.xingjianluo.creditriskscoringenginejava.model.DispositionRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 账户级可疑活动归集案件的查询与处置入口。
 */
@RestController
@RequestMapping("/api/account-cases")
public class AccountCaseController {

    private final AccountCaseService accountCaseService;

    public AccountCaseController(AccountCaseService accountCaseService) {
        this.accountCaseService = accountCaseService;
    }

    @GetMapping
    public List<AccountCase> all() {
        return accountCaseService.all();
    }

    @GetMapping("/{caseId}")
    public AccountCase get(@PathVariable String caseId) {
        return accountCaseService.get(caseId);
    }

    @GetMapping("/{caseId}/events")
    public List<CaseEvent> events(@PathVariable String caseId) {
        return accountCaseService.events(caseId);
    }

    /** 按交易查询其归集到的账户案件；未归集返回 404（可区分错误码）。 */
    @GetMapping("/by-transaction/{transactionId}")
    public ResponseEntity<?> byTransaction(@PathVariable String transactionId) {
        return accountCaseService.findByTransactionId(transactionId)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElse(ResponseEntity.status(404).body(Map.of(
                        "errorCode", "ACCOUNT_CASE_NOT_FOUND",
                        "message", "交易未归集到账户案件: " + transactionId)));
    }

    @PostMapping("/dispositions")
    public AccountCase apply(@RequestBody DispositionRequest request) {
        return accountCaseService.apply(request);
    }
}
