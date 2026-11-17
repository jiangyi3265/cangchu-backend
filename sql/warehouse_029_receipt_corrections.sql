-- Stop native writers and verify a backup before applying. Original receipts remain immutable.
CREATE TABLE wh_asn_correction (
 id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
 asn_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 receipt_line_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 quantity BIGINT NOT NULL,
 revision BIGINT NOT NULL,
 status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 approved_by BIGINT NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 approval_json LONGTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
 approval_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 result_json LONGTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
 result_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 CONSTRAINT fk_asn_correction_asn FOREIGN KEY(asn_id) REFERENCES wh_record(id),
 CONSTRAINT fk_asn_correction_line FOREIGN KEY(receipt_line_id) REFERENCES wh_asn_receipt_line(id),
 CONSTRAINT ck_asn_correction_state CHECK(quantity>0 AND quantity<=10000000 AND revision>0 AND status IN('APPROVED','EXECUTED','CANCELLED')),
 CONSTRAINT ck_asn_correction_approval_json CHECK(JSON_VALID(approval_json)),
 CONSTRAINT ck_asn_correction_result_json CHECK(JSON_VALID(result_json)),
 KEY ix_asn_correction_line(receipt_line_id,status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;



CREATE TABLE wh_finance_review_case (
 id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
 correction_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
 charge_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 client_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 warehouse_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 revision BIGINT NOT NULL,
 status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 document_json LONGTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
 document_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 CONSTRAINT fk_finance_review_correction FOREIGN KEY(correction_id) REFERENCES wh_asn_correction(id),
 CONSTRAINT fk_finance_review_charge FOREIGN KEY(charge_id) REFERENCES wh_record(id),
 CONSTRAINT fk_finance_review_client FOREIGN KEY(client_id) REFERENCES wh_record(id),
 CONSTRAINT fk_finance_review_warehouse FOREIGN KEY(warehouse_id) REFERENCES wh_record(id),
 CONSTRAINT ck_finance_review_state CHECK(revision>0 AND status IN('OPEN','LINKED','RETAINED')),
 CONSTRAINT ck_finance_review_case_document_json CHECK(JSON_VALID(document_json)),
 KEY ix_finance_review_client(client_id,status,created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE wh_finance_review_scope (
 case_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 warehouse_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 PRIMARY KEY(case_id,warehouse_id),
 CONSTRAINT fk_finance_review_scope_case FOREIGN KEY(case_id) REFERENCES wh_finance_review_case(id),
 CONSTRAINT fk_finance_review_scope_warehouse FOREIGN KEY(warehouse_id) REFERENCES wh_record(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
