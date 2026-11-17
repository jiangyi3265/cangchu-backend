-- Stop native writers and verify a backup. Historical snapshots are never rewritten.
CREATE TABLE wh_storage_identity_review (
  id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
  client_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  from_date DATE NOT NULL,
  to_date DATE NOT NULL,
  revision BIGINT NOT NULL,
  status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  document_json LONGTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  document_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  CONSTRAINT fk_storage_identity_client FOREIGN KEY(client_id) REFERENCES wh_record(id),
  CONSTRAINT uq_storage_identity_period UNIQUE(client_id,from_date,to_date),
  CONSTRAINT ck_storage_identity_period CHECK(from_date<=to_date AND revision>0 AND status IN ('DRAFT','APPROVED','REVOKED','CONSUMED')),
  CONSTRAINT ck_storage_identity_review_document_json CHECK(JSON_VALID(document_json))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE wh_storage_identity_event (
  id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
  review_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  revision BIGINT NOT NULL,
  action_name VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  actor_id BIGINT NOT NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  document_json LONGTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  document_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  CONSTRAINT fk_storage_identity_event_review FOREIGN KEY(review_id) REFERENCES wh_storage_identity_review(id),
  CONSTRAINT uq_storage_identity_event_revision UNIQUE(review_id,revision),
  CONSTRAINT ck_storage_identity_event_action CHECK(revision>0 AND action_name IN ('SAVE','APPROVE','REVOKE','CONSUME')),
  CONSTRAINT ck_storage_identity_event_document_json CHECK(JSON_VALID(document_json))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
