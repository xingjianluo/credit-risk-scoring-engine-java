package com.github.xingjianluo.creditriskscoringenginejava.tests;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HTTP 入口统一异常契约：任何入口的输入异常都返回 4xx + 可区分 errorCode，
 * 不暴露系统级 500，不静默丢弃。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(FixedClockTestConfig.class)
class EntryPointContractWebTest {

    private static final Logger log = LoggerFactory.getLogger(EntryPointContractWebTest.class);

    @Autowired
    private TestRestTemplate rest;

    private ResponseEntity<Map> postRaw(String path, String json) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.postForEntity(path, new HttpEntity<>(json, headers), Map.class);
    }

    @Test
    @DisplayName("非法流水 POST 单笔/批量：200 + 可区分拒绝原因，而非 500")
    void invalidTxReturnsDistinguishableRejection() {
        ResponseEntity<Map> single = postRaw("/api/risk/transactions",
                "{\"transactionId\":\"WEB-BAD-1\",\"accountId\":\"WA\",\"amount\":-5,"
                        + "\"currency\":\"CNY\",\"eventTime\":\"2026-09-26T02:00:00Z\","
                        + "\"counterpartyAccount\":\"CP-1\"}");
        assertThat(single.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(single.getBody().get("accepted")).isEqualTo(false);
        Map rejection = (Map) single.getBody().get("rejection");
        assertThat(rejection.get("reason")).isEqualTo("NON_POSITIVE_AMOUNT");
        log.info("[UT-WEB] 单笔非法流水: accepted=false reason={}", rejection.get("reason"));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<java.util.List> batch = rest.postForEntity("/api/risk/transactions/batch",
                new HttpEntity<>("[{\"transactionId\":\"WEB-BAD-2\",\"accountId\":\"WA\",\"amount\":-5,"
                        + "\"currency\":\"CNY\",\"eventTime\":\"2026-09-26T02:00:00Z\","
                        + "\"counterpartyAccount\":\"CP-1\"},"
                        + "{\"transactionId\":\"WEB-OK-1\",\"accountId\":\"WA\",\"amount\":100,"
                        + "\"currency\":\"CNY\",\"eventTime\":\"2026-09-26T02:00:00Z\","
                        + "\"counterpartyAccount\":\"CP-1\"}]", headers),
                java.util.List.class);
        assertThat(batch.getStatusCode()).isEqualTo(HttpStatus.OK);
        java.util.List<Map> outcomes = (java.util.List<Map>) batch.getBody();
        assertThat(outcomes).hasSize(2);
        assertThat(outcomes.get(0).get("accepted")).isEqualTo(false);
        assertThat(outcomes.get(1).get("accepted")).isEqualTo(true);
        log.info("[UT-WEB] 批量混合: 非法条目拒绝、正常条目评分，互不影响");
    }

    @Test
    @DisplayName("JSON 无法解析 / 非法枚举 / 空请求体：400 + 可区分 errorCode")
    void malformedRequests() {
        ResponseEntity<Map> malformed = postRaw("/api/risk/transactions", "{not-json");
        assertThat(malformed.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(malformed.getBody().get("errorCode")).isEqualTo("MALFORMED_REQUEST_BODY");

        ResponseEntity<Map> badMode = postRaw("/api/risk/simulate",
                "{\"runId\":\"w1\",\"mode\":\"SIDEWAYS\",\"transactions\":[]}");
        assertThat(badMode.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(badMode.getBody().get("errorCode")).isEqualTo("INVALID_ARRIVAL_MODE");

        ResponseEntity<Map> emptyBody = rest.postForEntity("/api/risk/simulate", null, Map.class);
        assertThat(emptyBody.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(emptyBody.getBody().get("errorCode")).isEqualTo("MISSING_REQUEST_BODY");

        ResponseEntity<Map> badBatch = postRaw("/api/cases/dispositions/batch",
                "{\"accountId\":\"A\",\"caseIds\":[\"CASE-1\"],\"action\":\"RESOLVE\"}");
        assertThat(badBatch.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(badBatch.getBody().get("errorCode")).isEqualTo("INVALID_BATCH_DISPOSITION_REQUEST");
        log.info("[UT-WEB] 400 契约: MALFORMED_REQUEST_BODY / INVALID_ARRIVAL_MODE / "
                + "MISSING_REQUEST_BODY / INVALID_BATCH_DISPOSITION_REQUEST");
    }

    @Test
    @DisplayName("模拟回放入口：非法流水与正常流水混合，报告计数正确且留痕一致")
    void simulateWithMixedInputs() {
        ResponseEntity<Map> resp = postRaw("/api/risk/simulate",
                "{\"runId\":\"web-sim-1\",\"mode\":\"OUT_OF_ORDER\",\"transactions\":["
                        + "{\"transactionId\":\"SIM-OK-1\",\"accountId\":\"SA\",\"amount\":100,"
                        + "\"currency\":\"CNY\",\"eventTime\":\"2026-09-26T02:00:00Z\","
                        + "\"counterpartyAccount\":\"CP-1\"},"
                        + "{\"transactionId\":\"SIM-BAD-1\",\"accountId\":\"SA\",\"amount\":-1,"
                        + "\"currency\":\"CNY\",\"eventTime\":\"2026-09-26T02:00:00Z\","
                        + "\"counterpartyAccount\":\"CP-1\"}]}");
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map report = resp.getBody();
        assertThat(report.get("accepted")).isEqualTo(1);
        assertThat(report.get("rejected")).isEqualTo(1);
        log.info("[UT-WEB] 模拟回放混合输入: accepted={} rejected={}",
                report.get("accepted"), report.get("rejected"));
    }

    @Test
    @DisplayName("不存在的资源：404 + 可区分 errorCode")
    void notFoundContracts() {
        ResponseEntity<Map> caseResp = rest.getForEntity("/api/cases/CASE-404404", Map.class);
        assertThat(caseResp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(caseResp.getBody().get("errorCode")).isEqualTo("CASE_NOT_FOUND");

        ResponseEntity<Map> txResp = rest.getForEntity("/api/risk/transactions/NOPE-1", Map.class);
        assertThat(txResp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(txResp.getBody().get("errorCode")).isEqualTo("TRANSACTION_NOT_FOUND");
        log.info("[UT-WEB] 404 契约: CASE_NOT_FOUND / TRANSACTION_NOT_FOUND");
    }
}
