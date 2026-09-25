package com.github.xingjianluo.creditriskscoringenginejava.tests;

import com.github.xingjianluo.creditriskscoringenginejava.model.Transaction;

import java.math.BigDecimal;
import java.time.Instant;

/** 测试用流水构造辅助。 */
public final class Transactions {

    public static final Instant BASE = Instant.parse("2026-09-26T02:00:00Z");

    private Transactions() {
    }

    public static TxBuilder builder(String id, String account) {
        return new TxBuilder(id, account);
    }

    public static final class TxBuilder {
        private final String id;
        private final String account;
        private BigDecimal amount = new BigDecimal("1000");
        private String currency = "CNY";
        private Instant eventTime = BASE;
        private String cp = "CP-1";
        private String country = "CN";
        private String channel = "WEB";

        TxBuilder(String id, String account) {
            this.id = id;
            this.account = account;
        }

        public TxBuilder amount(String v) { this.amount = new BigDecimal(v); return this; }
        public TxBuilder amount(BigDecimal v) { this.amount = v; return this; }
        public TxBuilder currency(String v) { this.currency = v; return this; }
        public TxBuilder time(Instant v) { this.eventTime = v; return this; }
        public TxBuilder time(String iso) { this.eventTime = Instant.parse(iso); return this; }
        public TxBuilder cp(String v) { this.cp = v; return this; }
        public TxBuilder country(String v) { this.country = v; return this; }
        public TxBuilder channel(String v) { this.channel = v; return this; }

        public Transaction build() {
            return new Transaction(id, account, amount, currency, eventTime, cp, country, channel);
        }
    }
}
