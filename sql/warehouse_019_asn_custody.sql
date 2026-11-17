-- Native inbound custody. Physical arrival is distinct from accepted, billable receipt.
CREATE TABLE IF NOT EXISTS wh_asn_custody (
  id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
  asn_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  product_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  location_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  quantity BIGINT NOT NULL,
  disposed_quantity BIGINT NOT NULL DEFAULT 0,
  accepted_quantity BIGINT NOT NULL DEFAULT 0,
  approved_extra BIGINT NOT NULL DEFAULT 0,
  layer_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL UNIQUE,
  batch_number VARCHAR(191) NOT NULL DEFAULT '',
  serial_number VARCHAR(191) NOT NULL DEFAULT '',
  expiry_date VARCHAR(10) NOT NULL DEFAULT '',
  inspection VARCHAR(16) NOT NULL DEFAULT 'PENDING',
  reason VARCHAR(500) NOT NULL,
  received_by BIGINT NOT NULL,
  received_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  CONSTRAINT fk_asn_custody_head FOREIGN KEY(asn_id) REFERENCES wh_record(id),
  CONSTRAINT fk_asn_custody_product FOREIGN KEY(product_id) REFERENCES wh_record(id),
  CONSTRAINT fk_asn_custody_location FOREIGN KEY(location_id) REFERENCES wh_record(id),
  CONSTRAINT fk_asn_custody_layer FOREIGN KEY(layer_id) REFERENCES wh_stock_layer(id),
  CONSTRAINT ck_asn_custody_quantity CHECK(quantity>0 AND quantity<=10000000 AND disposed_quantity>=0 AND disposed_quantity<=quantity AND accepted_quantity>=0 AND accepted_quantity<=disposed_quantity AND approved_extra>=0 AND approved_extra<=quantity),
  CONSTRAINT ck_asn_custody_inspection CHECK(inspection IN('PENDING','PASS','FAIL')),
  INDEX ix_asn_custody_head(asn_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE IF NOT EXISTS wh_asn_custody_event (
  id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
  custody_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  action_name VARCHAR(24) NOT NULL,
  quantity BIGINT NOT NULL DEFAULT 0,
  location_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
  reason VARCHAR(500) NOT NULL,
  actor_id BIGINT NOT NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  result_json LONGTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
  CONSTRAINT fk_asn_custody_event_item FOREIGN KEY(custody_id) REFERENCES wh_asn_custody(id),
  CONSTRAINT fk_asn_custody_event_location FOREIGN KEY(location_id) REFERENCES wh_record(id),
  CONSTRAINT ck_asn_custody_event_action CHECK(action_name IN('stage','inspect','approveOverage','putaway','scrap') AND quantity>=0),
  CONSTRAINT ck_asn_custody_event_result_json CHECK(result_json IS NULL OR JSON_VALID(result_json))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
