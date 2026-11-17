-- Original fees/invoices remain immutable financial evidence.
CREATE TABLE IF NOT EXISTS wh_finance_adjustment_policy (
 id INT PRIMARY KEY,
 revision BIGINT NOT NULL DEFAULT 0,
 policy_json JSON NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
INSERT INTO wh_finance_adjustment_policy(id) VALUES(1);
CREATE TABLE IF NOT EXISTS wh_finance_adjustment_approval (
 id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 adjustment_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 actor_id BIGINT NOT NULL,
 status VARCHAR(16) NOT NULL,
 plan_json JSON NOT NULL,
 plan_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 input_json JSON NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 CONSTRAINT fk_adjustment_approval_record FOREIGN KEY(adjustment_id) REFERENCES wh_record(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE IF NOT EXISTS wh_finance_adjustment_lock (
 charge_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 approval_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
 CONSTRAINT fk_adjustment_lock_charge FOREIGN KEY(charge_id) REFERENCES wh_record(id),
 CONSTRAINT fk_adjustment_lock_approval FOREIGN KEY(approval_id) REFERENCES wh_finance_adjustment_approval(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE IF NOT EXISTS wh_finance_adjustment_effect (
 id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 adjustment_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
 approval_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
 charge_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 invoice_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
 sequence_no BIGINT NOT NULL,
 route VARCHAR(32) NOT NULL,
 net DECIMAL(24,2) NOT NULL,
 tax DECIMAL(24,2) NOT NULL,
 effect_json JSON NOT NULL,
 effect_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 CONSTRAINT fk_adjustment_effect_record FOREIGN KEY(adjustment_id) REFERENCES wh_record(id),
 CONSTRAINT fk_adjustment_effect_approval FOREIGN KEY(approval_id) REFERENCES wh_finance_adjustment_approval(id),
 CONSTRAINT fk_adjustment_effect_charge FOREIGN KEY(charge_id) REFERENCES wh_record(id),
 CONSTRAINT fk_adjustment_effect_invoice FOREIGN KEY(invoice_id) REFERENCES wh_record(id),
 UNIQUE KEY uk_adjustment_effect_sequence(charge_id,sequence_no),
 KEY ix_adjustment_effect_invoice(invoice_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE IF NOT EXISTS wh_finance_credit_sequence (
 prefix VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 next_value BIGINT NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE IF NOT EXISTS wh_finance_credit_note (
 note_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 number VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
 invoice_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 effect_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
 content_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 CONSTRAINT fk_credit_note_record FOREIGN KEY(note_id) REFERENCES wh_record(id),
 CONSTRAINT fk_credit_note_invoice FOREIGN KEY(invoice_id) REFERENCES wh_record(id),
 CONSTRAINT fk_credit_note_effect FOREIGN KEY(effect_id) REFERENCES wh_finance_adjustment_effect(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
