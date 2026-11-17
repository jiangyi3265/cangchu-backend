-- Physical returns stay in custody until an inspected disposition is posted.
CREATE TABLE IF NOT EXISTS wh_return_receipt (
  id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
  return_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  allocation_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  location_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  quantity BIGINT NOT NULL,
  disposed_quantity BIGINT NOT NULL DEFAULT 0,
  layer_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
  inspection VARCHAR(16) NOT NULL DEFAULT 'PENDING',
  inspection_reason VARCHAR(500) NOT NULL DEFAULT '',
  inspected_by BIGINT NULL,
  inspected_at DATETIME(6) NULL,
  received_by BIGINT NOT NULL,
  received_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  CONSTRAINT fk_return_head FOREIGN KEY(return_id) REFERENCES wh_record(id),
  CONSTRAINT fk_return_allocation FOREIGN KEY(allocation_id) REFERENCES wh_order_allocation(id),
  CONSTRAINT fk_return_location FOREIGN KEY(location_id) REFERENCES wh_record(id),
  CONSTRAINT fk_return_layer FOREIGN KEY(layer_id) REFERENCES wh_stock_layer(id),
  CONSTRAINT ck_return_quantity CHECK(quantity>0 AND disposed_quantity>=0 AND disposed_quantity<=quantity),
  CONSTRAINT ck_return_inspection CHECK(inspection IN('PENDING','PASS','FAIL'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE IF NOT EXISTS wh_return_disposition (
  id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
  receipt_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  action_name VARCHAR(16) NOT NULL,
  quantity BIGINT NOT NULL,
  location_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
  reason VARCHAR(500) NOT NULL,
  actor_id BIGINT NOT NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  result_json JSON NULL,
  CONSTRAINT fk_return_disposition_receipt FOREIGN KEY(receipt_id) REFERENCES wh_return_receipt(id),
  CONSTRAINT fk_return_disposition_location FOREIGN KEY(location_id) REFERENCES wh_record(id),
  CONSTRAINT ck_return_disposition CHECK(action_name IN('restock','scrap') AND quantity>0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
