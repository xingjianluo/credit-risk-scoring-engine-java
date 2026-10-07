package com.github.xingjianluo.creditriskscoringenginejava.tests;

import com.github.xingjianluo.creditriskscoringenginejava.caseflow.AccountCaseService;
import com.github.xingjianluo.creditriskscoringenginejava.caseflow.ReviewCaseService;
import com.github.xingjianluo.creditriskscoringenginejava.ledger.TransactionLedger;
import com.github.xingjianluo.creditriskscoringenginejava.model.ArrivalMode;
import com.github.xingjianluo.creditriskscoringenginejava.model.BatchReport;
import com.github.xingjianluo.creditriskscoringenginejava.model.RejectionReason;
import com.github.xingjianluo.creditriskscoringenginejava.model.SubmissionOutcome;
import com.github.xingjianluo.creditriskscoringenginejava.model.Transaction;
import com.github.xingjianluo.creditriskscoringenginejava.service.LocalEventSimulator;
import com.github.xingjianluo.creditriskscoringenginejava.service.RiskScoringService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 受理入口统一异常契约：同一笔异常流水走单笔 / 批量 / 本地模拟回放 / 并发批量，
 * 得到的拒绝原因与留痕一致；任何入口都不抛系统级错误、不静默丢弃；
 * 异常输入不影响同批正常流水的评分与计数。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(FixedClockTestConfig.class)
class EntryPointConsistencyTest {

    private static final Logger log = LoggerFactory.getLogger(EntryPointConsistencyTest.class);

    @Autowired
    private RiskScoringService scoringService;
    @Autowired
    private LocalEventSimulator simulator;
    @Autowired
    private TransactionLedger ledger;
    @Autowired
    private ReviewCaseService caseService;
    @Autowired
    private AccountCaseService accountCaseService;
    @Autowired
    private MockMvc mockMvc;

    @BeforeEach
    void reset() {
        ledger.clear();
        caseService.clear();
        accountCaseService.clear();
    }

    /** 负金额非法流水。 */
    private static Transaction badAmount(String id) {
        return Transactions.builder(id, "EP1").amount(new BigDecimal("-10"))
                .time("2026-09-26T01:00:00Z").build();
    }

    /** 缺账户 ID 的非法流水。 */
    private static Transaction missingAccount(String id) {
        return Transactions.builder(id, null).amount("500")
                .time("2026-09-26T01:00:00Z").build();
    }

    /** 正常流水。 */
    private static Transaction good(String id, String account) {
        return Transactions.builder(id, account).amount("800")
                .time("2026-09-26T01:00:00Z").cp("CP-1").country("CN").build();
    }

    @Test
    @DisplayName("同一非法流水在单笔/批量/三种模拟回放入口下拒绝原因一致且留痕")
    void sameInvalidTxRejectedConsistentlyAcrossEntries() {
        // 1) 单笔入口
        SubmissionOutcome single = scoringService.submit(badAmount("E1"));
        assertThat(single.accepted()).isFalse();
        assertThat(single.rejection().reason()).isEqualTo(RejectionReason.NON_POSITIVE_AMOUNT);
        assertThat(ledger.rejectionOf("E1")).isPresent();
        log.info("[UT-ENTRY] 单笔入口拒绝: reason={} detail={}",
                single.rejection().reason(), single.rejection().detail());

        // 2) 批量入口（换 ID 避免与单笔的留痕幂等）
        SubmissionOutcome batch = scoringService.submitBatch(List.of(badAmount("E2"))).get(0);
        assertThat(batch.accepted()).isFalse();
        assertThat(batch.rejection().reason()).isEqualTo(single.rejection().reason());
        log.info("[UT-ENTRY] 批量入口拒绝: reason={}", batch.rejection().reason());

        // 3) 本地模拟回放：三种到达方式
        for (ArrivalMode mode : ArrivalMode.values()) {
            BatchReport report = simulator.run("ep-" + mode, List.of(badAmount("E3")), mode);
            assertThat(report.accepted()).isEqualTo(0);
            assertThat(report.rejected()).isEqualTo(1);
            SubmissionOutcome o = report.outcomes().get(0);
            assertThat(o.accepted()).isFalse();
            assertThat(o.rejection().reason()).isEqualTo(RejectionReason.NON_POSITIVE_AMOUNT);
            assertThat(ledger.rejectionOf("E3")).isPresent();
            log.info("[UT-ENTRY] 模拟回放 {} 拒绝: reason={} 留痕={}",
                    mode, o.rejection().reason(), ledger.rejectionOf("E3").isPresent());
        }
    }

    @Test
    @DisplayName("空流水/缺字段流水不抛系统级错误，各入口返回可区分拒绝原因")
    void nullAndIncompleteTxNeverThrowSystemError() {
        // 空流水（null）走单笔入口：此前会因 List.of(null) 抛 NPE
        SubmissionOutcome nullTx = scoringService.submit(null);
        assertThat(nullTx.accepted()).isFalse();
        assertThat(nullTx.rejection().reason()).isEqualTo(RejectionReason.MISSING_TRANSACTION_ID);
        log.info("[UT-ENTRY] 空流水单笔拒绝: reason={}", nullTx.rejection().reason());

        // 空 ID 流水走单笔入口
        SubmissionOutcome blankId = scoringService.submit(
                Transactions.builder("  ", "EP1").build());
        assertThat(blankId.accepted()).isFalse();
        assertThat(blankId.rejection().reason()).isEqualTo(RejectionReason.MISSING_TRANSACTION_ID);

        // 批量里混入 null 与缺账户流水：逐条拒绝，原因可区分
        List<SubmissionOutcome> outcomes = scoringService.submitBatch(
                java.util.Arrays.asList(null, missingAccount("E4"), good("E5", "EP1")));
        assertThat(outcomes.get(0).rejection().reason())
                .isEqualTo(RejectionReason.MISSING_TRANSACTION_ID);
        assertThat(outcomes.get(1).rejection().reason())
                .isEqualTo(RejectionReason.MISSING_ACCOUNT_ID);
        assertThat(outcomes.get(2).accepted()).isTrue();
        log.info("[UT-ENTRY] 混合批量: null->{} 缺账户->{} 正常->已评分",
                outcomes.get(0).rejection().reason(), outcomes.get(1).rejection().reason());

        // 无 ID 的拒绝也有留痕（匿名留痕列表），不静默丢弃
        assertThat(ledger.allRejections()).hasSizeGreaterThanOrEqualTo(3);
    }

    @Test
    @DisplayName("异常输入不影响同批正常流水的评分与计数（含并发批量）")
    void invalidTxDoesNotAffectValidOnesInBatch() throws Exception {
        List<Transaction> mixed = List.of(
                good("G1", "EP2"),
                badAmount("E6"),
                good("G2", "EP2"),
                missingAccount("E7"),
                good("G3", "EP3"));

        // 顺序批量
        List<SubmissionOutcome> outcomes = scoringService.submitBatch(mixed);
        long accepted = outcomes.stream().filter(SubmissionOutcome::accepted).count();
        assertThat(accepted).isEqualTo(3);
        assertThat(ledger.allScores()).hasSize(3);
        assertThat(ledger.allRejections()).hasSize(2);
        log.info("[UT-ENTRY] 混合批量: 接受={} 拒绝留痕={}", accepted, ledger.allRejections().size());

        // 并发批量（同一混合批次多线程同时提交）：计数与串行一致
        ledger.clear();
        caseService.clear();
        accountCaseService.clear();
        int threads = 6;
        CyclicBarrier barrier = new CyclicBarrier(threads);
        List<CompletableFuture<List<SubmissionOutcome>>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(CompletableFuture.supplyAsync(() -> {
                try {
                    barrier.await();
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
                return scoringService.submitBatch(mixed);
            }));
        }
        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
        assertThat(ledger.allScores()).hasSize(3);
        assertThat(ledger.allRejections()).hasSize(2);
        // 每个线程都拿到等长、同序的结果；正常流水均被接受
        for (CompletableFuture<List<SubmissionOutcome>> f : futures) {
            List<SubmissionOutcome> os = f.join();
            assertThat(os).hasSize(5);
            assertThat(os.get(0).accepted()).isTrue();
            assertThat(os.get(1).rejection().reason()).isEqualTo(RejectionReason.NON_POSITIVE_AMOUNT);
            assertThat(os.get(2).accepted()).isTrue();
            assertThat(os.get(3).rejection().reason()).isEqualTo(RejectionReason.MISSING_ACCOUNT_ID);
            assertThat(os.get(4).accepted()).isTrue();
        }
        log.info("[UT-ENTRY] 并发 {} 线程混合批量：评分={} 拒绝留痕={}（与串行一致）",
                threads, ledger.allScores().size(), ledger.allRejections().size());
    }

    @Test
    @DisplayName("HTTP 入口：单笔非法流水返回 200+拒绝结论（非 5xx），批量入口逐条可区分")
    void httpEntriesReturnDistinguishableResults() throws Exception {
        // 单笔非法流水：200，body 为拒绝结论（可区分原因码）
        mockMvc.perform(post("/api/risk/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"transactionId\":\"H1\",\"accountId\":\"EP4\",\"amount\":-5,"
                                + "\"currency\":\"CNY\",\"eventTime\":\"2026-09-26T01:00:00Z\","
                                + "\"counterpartyAccount\":\"CP-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(false))
                .andExpect(jsonPath("$.rejection.reason").value("NON_POSITIVE_AMOUNT"));

        // 批量入口：正常+非法混合，逐条结论
        MvcResult result = mockMvc.perform(post("/api/risk/transactions/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("["
                                + "{\"transactionId\":\"H2\",\"accountId\":\"EP4\",\"amount\":800,"
                                + "\"currency\":\"CNY\",\"eventTime\":\"2026-09-26T01:00:00Z\","
                                + "\"counterpartyAccount\":\"CP-1\"},"
                                + "{\"transactionId\":\"H3\",\"accountId\":\"EP4\",\"amount\":500,"
                                + "\"currency\":\"XXX\",\"eventTime\":\"2026-09-26T01:00:00Z\","
                                + "\"counterpartyAccount\":\"CP-1\"}"
                                + "]"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].accepted").value(true))
                .andExpect(jsonPath("$[1].accepted").value(false))
                .andExpect(jsonPath("$[1].rejection.reason").value("UNSUPPORTED_CURRENCY"))
                .andReturn();
        log.info("[UT-ENTRY] HTTP 批量响应: {}", result.getResponse().getContentAsString());
        // 正常流水评分落账，非法流水拒绝留痕
        assertThat(ledger.scoreOf("H2")).isPresent();
        assertThat(ledger.rejectionOf("H3")).isPresent();
    }

    @Test
    @DisplayName("HTTP 入口：格式损坏/参数非法返回可区分 400，而非不透明 500")
    void httpMalformedRequestsReturnDistinguishable400() throws Exception {
        // JSON 格式损坏
        mockMvc.perform(post("/api/risk/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not-a-json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("MALFORMED_REQUEST_BODY"));

        // 字段类型错误（amount 为字符串）
        mockMvc.perform(post("/api/risk/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"transactionId\":\"H9\",\"amount\":\"abc\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("MALFORMED_REQUEST_BODY"));

        // 模拟回放到达方式非法
        mockMvc.perform(post("/api/risk/simulate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"runId\":\"bad-mode\",\"mode\":\"SIDEWAYS\",\"transactions\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_ARGUMENT"));
        log.info("[UT-ENTRY] 损坏 JSON / 非法参数均返回可区分 400，无系统级错误");
    }
}
