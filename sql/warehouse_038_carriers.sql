-- Stop writers and verify a backup before applying. Remote creation is disabled by default.
-- Closed requests prevent delayed HTTP submissions after the operator resolves a lost response.
CREATE TABLE wh_carrier_closed_request (
 actor_id BIGINT NOT NULL,
 request_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 request_kind VARCHAR(8) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 resource_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 PRIMARY KEY(actor_id,request_id,request_kind),
 CONSTRAINT ck_carrier_closed_kind CHECK(request_kind IN('book','config'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE wh_carrier_connection (
 id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 name VARCHAR(191) NOT NULL,
 client_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 warehouse_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 config_json LONGTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
 credentials_cipher TEXT NULL,
 account_key CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
 enabled BOOLEAN NOT NULL DEFAULT FALSE,
 revision BIGINT NOT NULL DEFAULT 1,
 last_request_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 last_input_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 updated_by BIGINT NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 CONSTRAINT fk_carrier_connection_client FOREIGN KEY(client_id) REFERENCES wh_record(id),
 CONSTRAINT fk_carrier_connection_warehouse FOREIGN KEY(warehouse_id) REFERENCES wh_record(id),
 CONSTRAINT ck_carrier_connection_config_json CHECK(JSON_VALID(config_json)),
 CONSTRAINT ck_carrier_connection_state CHECK(revision>0 AND enabled IN(0,1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE wh_carrier_shipment (
 id CHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 connection_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 order_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 client_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 warehouse_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 actor_id BIGINT NOT NULL,
 request_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 reference VARCHAR(25) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
 state VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 connection_revision BIGINT NOT NULL,
 snapshot_json LONGTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
 config_json LONGTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
 identity_json LONGTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL DEFAULT ('{}'),
 error_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT '',
 revision BIGINT NOT NULL DEFAULT 1,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 CONSTRAINT fk_carrier_shipment_connection FOREIGN KEY(connection_id) REFERENCES wh_carrier_connection(id),
 CONSTRAINT fk_carrier_shipment_order FOREIGN KEY(order_id) REFERENCES wh_record(id),
 CONSTRAINT ck_carrier_shipment_snapshot_json CHECK(JSON_VALID(snapshot_json)),
 CONSTRAINT ck_carrier_shipment_config_json CHECK(JSON_VALID(config_json)),
 CONSTRAINT ck_carrier_shipment_identity_json CHECK(JSON_VALID(identity_json)),
 CONSTRAINT ck_carrier_shipment_state CHECK(revision>0 AND connection_revision>0 AND state IN('PREPARING','SENDING','CREATED','LABELLED','REJECTED','UNKNOWN')),
 UNIQUE KEY uk_carrier_request(actor_id,request_id),
 KEY ix_carrier_order(order_id,state),
 KEY ix_carrier_scope(client_id,warehouse_id,created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE wh_carrier_label (
 shipment_id CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 parcel_index INT NOT NULL,
 package_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 tracking_number VARCHAR(128) NOT NULL,
 label_text MEDIUMTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
 sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 PRIMARY KEY(shipment_id,parcel_index),
 CONSTRAINT fk_carrier_label_shipment FOREIGN KEY(shipment_id) REFERENCES wh_carrier_shipment(id),
 CONSTRAINT fk_carrier_label_package FOREIGN KEY(package_id) REFERENCES wh_order_package(id),
 CONSTRAINT ck_carrier_label_position CHECK(parcel_index>=0 AND parcel_index<99)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
