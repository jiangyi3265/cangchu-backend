-- Finance workflow evidence. Apply after warehouse_001.sql and warehouse_003_orders.sql.
-- Pricing rules, charges and invoices retain their native wh_record identities.
CREATE TABLE IF NOT EXISTS wh_finance_period (
 id CHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 rule_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 client_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 from_date DATE NOT NULL,
 to_date DATE NOT NULL,
 currency VARCHAR(3) NOT NULL,
 snapshot_json JSON NOT NULL,
 settled_count BIGINT NOT NULL DEFAULT 0,
 settled_amount DECIMAL(24,2) NOT NULL DEFAULT 0,
 CONSTRAINT fk_finance_period_rule FOREIGN KEY(rule_id) REFERENCES wh_record(id),
 CONSTRAINT fk_finance_period_client FOREIGN KEY(client_id) REFERENCES wh_record(id),
 UNIQUE KEY uk_finance_period(rule_id,from_date,to_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE IF NOT EXISTS wh_finance_tier_order (
 order_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 period_id CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 warehouse_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 CONSTRAINT fk_finance_tier_order FOREIGN KEY(order_id) REFERENCES wh_record(id),
 CONSTRAINT fk_finance_tier_period FOREIGN KEY(period_id) REFERENCES wh_finance_period(id),
 CONSTRAINT fk_finance_tier_warehouse FOREIGN KEY(warehouse_id) REFERENCES wh_record(id),
 KEY ix_finance_tier_period(period_id,order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE IF NOT EXISTS wh_finance_source (
 source_key VARCHAR(191) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 charge_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
 evidence_json JSON NOT NULL,
 CONSTRAINT fk_finance_source_charge FOREIGN KEY(charge_id) REFERENCES wh_record(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE IF NOT EXISTS wh_finance_invoice_line (
 invoice_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 charge_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 snapshot_json JSON NOT NULL,
 PRIMARY KEY(invoice_id,charge_id),
 CONSTRAINT fk_finance_line_invoice FOREIGN KEY(invoice_id) REFERENCES wh_record(id),
 CONSTRAINT fk_finance_line_charge FOREIGN KEY(charge_id) REFERENCES wh_record(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE IF NOT EXISTS wh_finance_invoice_claim (
 charge_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 invoice_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 CONSTRAINT fk_finance_claim_charge FOREIGN KEY(charge_id) REFERENCES wh_record(id),
 CONSTRAINT fk_finance_claim_invoice FOREIGN KEY(invoice_id) REFERENCES wh_record(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
