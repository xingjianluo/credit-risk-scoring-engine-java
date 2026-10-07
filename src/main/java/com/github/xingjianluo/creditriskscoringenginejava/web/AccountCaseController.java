package com.github.xingjianluo.creditriskscoringenginejava.web;

import com.github.xingjianluo.creditriskscoringenginejava.caseflow.AccountCaseService;
import com.github.xingjianluo.creditriskscoringenginejava.exception.CaseNotFoundException;
import com.github.xingjianluo.creditriskscoringenginejava.model.AccountCase;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 账户级归集案件查询入口。
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
        return accountCaseService.find(caseId).orElseThrow(() -> new CaseNotFoundException(caseId));
    }

    @GetMapping("/by-account/{accountId}")
    public List<AccountCase> byAccount(@PathVariable String accountId) {
        return accountCaseService.findByAccount(accountId);
    }
}
