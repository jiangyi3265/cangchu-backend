-- Stop writers and verify a backup before applying. No remote provider writes.
CREATE TABLE wh_integration_authority (
 connection_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 source_key CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
 source_identity VARCHAR(512) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 cutover_at DATETIME(6) NOT NULL,
 fulfillment_mode VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 active BOOLEAN NOT NULL DEFAULT FALSE,
 reason VARCHAR(500) NOT NULL,
 revision BIGINT NOT NULL DEFAULT 1,
 updated_by BIGINT NOT NULL,
 updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 CONSTRAINT fk_integration_authority_connection FOREIGN KEY(connection_id) REFERENCES wh_integration_connection(id),
 CONSTRAINT ck_integration_authority_state CHECK(revision>0 AND active IN(0,1) AND fulfillment_mode IN('CORE','LEGACY_METADATA'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE wh_integration_mapping (
 id CHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 connection_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 external_product_id VARCHAR(19) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 external_variation_id VARCHAR(19) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 external_sku VARCHAR(191) NOT NULL,
 product_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 active BOOLEAN NOT NULL DEFAULT TRUE,
 revision BIGINT NOT NULL DEFAULT 1,
 updated_by BIGINT NOT NULL,
 updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 CONSTRAINT fk_integration_mapping_connection FOREIGN KEY(connection_id) REFERENCES wh_integration_connection(id),
 CONSTRAINT fk_integration_mapping_product FOREIGN KEY(product_id) REFERENCES wh_record(id),
 CONSTRAINT ck_integration_mapping_state CHECK(revision>0 AND active IN(0,1)),
 UNIQUE KEY uk_integration_mapping_source(connection_id,external_product_id,external_variation_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE wh_integration_order (
 inbox_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 connection_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 source_key CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 external_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 order_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
 evidence_json LONGTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
 actor_id BIGINT NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 CONSTRAINT fk_integration_order_inbox FOREIGN KEY(inbox_id) REFERENCES wh_integration_inbox(id),
 CONSTRAINT fk_integration_order_connection FOREIGN KEY(connection_id) REFERENCES wh_integration_connection(id),
 CONSTRAINT fk_integration_order_record FOREIGN KEY(order_id) REFERENCES wh_record(id),
 CONSTRAINT ck_integration_order_evidence_json CHECK(JSON_VALID(evidence_json)),
 UNIQUE KEY uk_integration_order_source(source_key,external_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
