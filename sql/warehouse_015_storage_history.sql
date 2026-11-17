-- Incremental history shares the originating business transaction.
CREATE TABLE IF NOT EXISTS wh_storage_control (
 id INT PRIMARY KEY,
 enabled BOOLEAN NOT NULL DEFAULT FALSE,
 baseline_batch VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
 started_at DATETIME(6) NULL,
 last_sequence BIGINT NOT NULL DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
INSERT INTO wh_storage_control(id) VALUES(1);
CREATE TABLE IF NOT EXISTS wh_storage_batch (
 id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 sequence_no BIGINT NOT NULL UNIQUE,
 effective_at DATETIME(6) NOT NULL,
 baseline BOOLEAN NOT NULL,
 change_count INT NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE IF NOT EXISTS wh_storage_change (
 batch_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 sequence_no BIGINT NOT NULL,
 entity_kind VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 entity_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 client_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT '',
 payload_json JSON NOT NULL,
 payload_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 PRIMARY KEY(batch_id,entity_kind,entity_id),
 CONSTRAINT fk_storage_change_batch FOREIGN KEY(batch_id) REFERENCES wh_storage_batch(id),
 KEY ix_storage_history(client_id,entity_kind,entity_id,sequence_no),
 KEY ix_storage_sequence(sequence_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE IF NOT EXISTS wh_storage_billed_day (
 client_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 billing_day DATE NOT NULL,
 charge_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 PRIMARY KEY(client_id,billing_day),
 CONSTRAINT fk_storage_billed_client FOREIGN KEY(client_id) REFERENCES wh_record(id),
 CONSTRAINT fk_storage_billed_charge FOREIGN KEY(charge_id) REFERENCES wh_record(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
