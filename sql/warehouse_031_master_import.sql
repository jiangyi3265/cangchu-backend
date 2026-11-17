-- Stop writers and verify a backup. Previews do not change business master records.
CREATE TABLE wh_master_import (
 id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
 kind VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 actor_id BIGINT NOT NULL,
 revision BIGINT NOT NULL,
 status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 document_json LONGTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
 document_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 CONSTRAINT ck_master_import_state CHECK(revision>0 AND status IN('PREVIEW','COMMITTED','CANCELLED')),
 CONSTRAINT ck_master_import_document_json CHECK(JSON_VALID(document_json)),
 KEY ix_master_import_actor(actor_id,kind,created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
