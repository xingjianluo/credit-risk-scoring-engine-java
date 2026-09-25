package com.github.xingjianluo.creditriskscoringenginejava.tests;

import com.github.xingjianluo.creditriskscoringenginejava.domain.RejectReason;
import com.github.xingjianluo.creditriskscoringenginejava.domain.TransactionRequest;
import com.github.xingjianluo.creditriskscoringenginejava.domain.ValidationOutcome;
import com.github.xingjianluo.creditriskscoringenginejava.service.TransactionValidator;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 单测：字段缺失 / 金额非法 / 时间异常必须返回互不相同的拒绝原因，绝不静默丢弃。
 */
class TransactionValidatorTest {

    private static final Logger log = LoggerFactory.getLogger(TransactionValidatorTest.class);

    private final TransactionValidator validator = new TransactionValidator();
    private final Instant now = Instant.parse("2026-09-20T12:00:00Z");
    private final Instant past = Instant.parse("2026-09-20T10:00:00Z");

    private TransactionRequest req(String id, String account, BigDecimal amount,
                                   Instant eventTime) {
        return new TransactionRequest(id, account, amount, "P1", "US", eventTime);
    }

    @Test
    void allRejectReasonsAreDistinguishable() {
        record Case(String name, TransactionRequest request, RejectReason expected) {
        }
        var cases = java.util.List.of(
                new Case("缺少流水号", req("  ", "A1", BigDecimal.TEN, past),
                        RejectReason.MISSING_TRANSACTION_ID),
                new Case("缺少账户号", req("t1", "  ", BigDecimal.TEN, past),
                        RejectReason.MISSING_ACCOUNT_ID),
                new Case("缺少金额", req("t2", "A1", null, past),
                        RejectReason.MISSING_AMOUNT),
                new Case("金额为零", req("t3", "A1", BigDecimal.ZERO, past),
                        RejectReason.NON_POSITIVE_AMOUNT),
                new Case("金额为负", req("t4", "A1", new BigDecimal("-5"), past),
                        RejectReason.NON_POSITIVE_AMOUNT),
                new Case("缺少时间", req("t5", "A1", BigDecimal.TEN, null),
                        RejectReason.MISSING_EVENT_TIME),
                new Case("未来时间", req("t6", "A1", BigDecimal.TEN,
                        now.plusSeconds(1)), RejectReason.FUTURE_EVENT_TIME)
        );
        for (Case c : cases) {
            ValidationOutcome vo = validator.validate(c.request(), now);
            log.info("[测试输入] {} -> [判定依据] accepted={} reason={} detail={}",
                    c.name(), vo.accepted(), vo.reason(), vo.detail());
            assertFalse(vo.accepted(), c.name() + " 必须被拒绝");
            assertEquals(c.expected(), vo.reason(),
                    c.name() + " 的拒绝原因应可区分");
            assertNotNull(vo.detail());
        }

        long distinctReasons = cases.stream().map(Case::expected).distinct().count();
        assertTrue(distinctReasons >= 6, "六类问题必须对应至少 6 个可区分原因");
    }

    @Test
    void validRequestAcceptedAndNormalized() {
        ValidationOutcome vo = validator.validate(
                new TransactionRequest(" t7 ", " A1 ", new BigDecimal("12.30"),
                        " p9 ", " us ", past), now);
        log.info("[测试输入] 合法流水（带空白、小写辖区）-> [判定依据] accepted={}",
                vo.accepted());
        assertTrue(vo.accepted());
        assertEquals("t7", vo.event().transactionId());
        assertEquals("A1", vo.event().accountId());
        assertEquals("US", vo.event().jurisdiction());
    }
}
