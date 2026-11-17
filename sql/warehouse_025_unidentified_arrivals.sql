-- Stop writers and verify a backup. Unidentified goods are physical custody, not SKU stock.
CREATE TABLE wh_unidentified_occupancy (
  location_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
  quantity BIGINT NOT NULL DEFAULT 0,
  CONSTRAINT fk_unidentified_occupancy_location FOREIGN KEY(location_id) REFERENCES wh_record(id),
  CONSTRAINT ck_unidentified_occupancy_quantity CHECK(quantity>=0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE wh_asn_unidentified (
  id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
  asn_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  client_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  warehouse_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  location_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  quantity BIGINT NOT NULL,
  mapped_quantity BIGINT NOT NULL DEFAULT 0,
  returned_quantity BIGINT NOT NULL DEFAULT 0,
  scrapped_quantity BIGINT NOT NULL DEFAULT 0,
  observed_barcode VARCHAR(200) NOT NULL DEFAULT '',
  observed_description VARCHAR(500) NOT NULL,
  evidence_reference VARCHAR(200) NOT NULL,
  batch_number VARCHAR(191) NOT NULL DEFAULT '',
  serial_number VARCHAR(191) NOT NULL DEFAULT '',
  expiry_date VARCHAR(10) NOT NULL DEFAULT '',
  reason VARCHAR(500) NOT NULL,
  actor_id BIGINT NOT NULL,
  arrived_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  CONSTRAINT fk_asn_unidentified_head FOREIGN KEY(asn_id) REFERENCES wh_record(id),
  CONSTRAINT fk_asn_unidentified_client FOREIGN KEY(client_id) REFERENCES wh_record(id),
  CONSTRAINT fk_asn_unidentified_warehouse FOREIGN KEY(warehouse_id) REFERENCES wh_record(id),
  CONSTRAINT fk_asn_unidentified_location FOREIGN KEY(location_id) REFERENCES wh_record(id),
  CONSTRAINT ck_asn_unidentified_quantity CHECK(quantity>0 AND quantity<=10000000 AND mapped_quantity>=0 AND returned_quantity>=0 AND scrapped_quantity>=0 AND mapped_quantity+returned_quantity+scrapped_quantity<=quantity),
  INDEX ix_asn_unidentified_head(asn_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE wh_asn_identity_approval (
  id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
  arrival_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  decision VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  quantity BIGINT NOT NULL,
  executed_quantity BIGINT NOT NULL DEFAULT 0,
  cancelled_quantity BIGINT NOT NULL DEFAULT 0,
  product_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
  product_revision BIGINT NULL,
  batch_number VARCHAR(191) NOT NULL DEFAULT '',
  serial_number VARCHAR(191) NOT NULL DEFAULT '',
  expiry_date VARCHAR(10) NOT NULL DEFAULT '',
  recipient VARCHAR(200) NOT NULL DEFAULT '',
  destination VARCHAR(1000) NOT NULL DEFAULT '',
  reference VARCHAR(200) NOT NULL,
  reason VARCHAR(500) NOT NULL,
  actor_id BIGINT NOT NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  evidence_json LONGTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  CONSTRAINT fk_identity_approval_arrival FOREIGN KEY(arrival_id) REFERENCES wh_asn_unidentified(id),
  CONSTRAINT fk_identity_approval_product FOREIGN KEY(product_id) REFERENCES wh_record(id),
  CONSTRAINT ck_identity_approval_quantity CHECK(quantity>0 AND quantity<=10000000 AND executed_quantity>=0 AND cancelled_quantity>=0 AND executed_quantity+cancelled_quantity<=quantity),
  CONSTRAINT ck_identity_approval_decision CHECK((decision='identify' AND product_id IS NOT NULL AND product_revision IS NOT NULL) OR (decision IN ('returnSupplier','scrap') AND product_id IS NULL AND product_revision IS NULL)),
  CONSTRAINT ck_asn_identity_approval_evidence_json CHECK(JSON_VALID(evidence_json))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE wh_asn_unidentified_event (
  id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
  arrival_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  approval_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
  action_name VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  quantity BIGINT NOT NULL,
  custody_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL UNIQUE,
  reason VARCHAR(500) NOT NULL,
  reference VARCHAR(200) NOT NULL DEFAULT '',
  actor_id BIGINT NOT NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  result_json LONGTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  CONSTRAINT fk_unidentified_event_arrival FOREIGN KEY(arrival_id) REFERENCES wh_asn_unidentified(id),
  CONSTRAINT fk_unidentified_event_approval FOREIGN KEY(approval_id) REFERENCES wh_asn_identity_approval(id),
  CONSTRAINT fk_unidentified_event_custody FOREIGN KEY(custody_id) REFERENCES wh_asn_custody(id),
  CONSTRAINT ck_unidentified_event_action CHECK(action_name IN ('unknownArrive','unknownApprove','unknownExecute','unknownCancel') AND quantity>0),
  CONSTRAINT ck_asn_unidentified_event_result_json CHECK(JSON_VALID(result_json))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
