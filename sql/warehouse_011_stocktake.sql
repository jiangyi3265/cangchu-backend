-- Physical counts retain their snapshot, count and approval evidence.
CREATE TABLE wh_stocktake_lock (
 client_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 warehouse_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 location_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 stocktake_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 PRIMARY KEY(client_id,warehouse_id,location_id),
 CONSTRAINT fk_wh_count_lock_owner FOREIGN KEY(stocktake_id) REFERENCES wh_record(id),
 CONSTRAINT fk_wh_count_lock_location FOREIGN KEY(location_id) REFERENCES wh_record(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE wh_stocktake_line (
 id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 stocktake_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 product_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 location_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 inventory_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
 batch_number VARCHAR(191) NOT NULL DEFAULT '',
 serial_number VARCHAR(191) NOT NULL DEFAULT '',
 expiry_date VARCHAR(10) NOT NULL DEFAULT '',
 expected_quantity BIGINT NOT NULL,
 expected_reserved BIGINT NOT NULL,
 inventory_revision BIGINT NULL,
 counted_quantity BIGINT NULL,
 count_reason VARCHAR(500) NOT NULL DEFAULT '',
 counted_by BIGINT NULL,
 counted_at TIMESTAMP(6) NULL,
 unexpected BOOLEAN NOT NULL DEFAULT FALSE,
 result_json JSON NULL,
 CONSTRAINT fk_wh_count_line_owner FOREIGN KEY(stocktake_id) REFERENCES wh_record(id),
 CONSTRAINT fk_wh_count_line_product FOREIGN KEY(product_id) REFERENCES wh_record(id),
 CONSTRAINT fk_wh_count_line_location FOREIGN KEY(location_id) REFERENCES wh_record(id),
 CONSTRAINT fk_wh_count_line_inventory FOREIGN KEY(inventory_id) REFERENCES wh_inventory(id),
 CONSTRAINT ck_wh_count_values CHECK(expected_quantity>=0 AND expected_reserved>=0 AND expected_reserved<=expected_quantity AND (counted_quantity IS NULL OR counted_quantity>=0)),
 UNIQUE KEY uk_wh_count_pool(stocktake_id,product_id,location_id,batch_number,serial_number,expiry_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
