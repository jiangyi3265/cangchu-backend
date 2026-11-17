-- Native ASN receipts. Apply after warehouse_005_finance.sql to the Java database.
-- Receipt rows and price snapshots are immutable; corrections need a separate workflow.
CREATE TABLE IF NOT EXISTS wh_asn_receipt (
 id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 asn_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 actor_id BIGINT NOT NULL,
 received_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 CONSTRAINT fk_wh_asn_receipt_asn FOREIGN KEY(asn_id) REFERENCES wh_record(id),
 KEY ix_wh_asn_receipt(asn_id,received_at,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE IF NOT EXISTS wh_asn_receipt_line (
 id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 receipt_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 product_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 location_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 inventory_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 layer_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
 quantity BIGINT NOT NULL,
 batch_number VARCHAR(191) NOT NULL DEFAULT '',
 serial_number VARCHAR(191) NOT NULL DEFAULT '',
 expiry_date VARCHAR(10) NOT NULL DEFAULT '',
 CONSTRAINT ck_wh_asn_receipt_quantity CHECK(quantity>0),
 CONSTRAINT fk_wh_asn_line_receipt FOREIGN KEY(receipt_id) REFERENCES wh_asn_receipt(id),
 CONSTRAINT fk_wh_asn_line_product FOREIGN KEY(product_id) REFERENCES wh_record(id),
 CONSTRAINT fk_wh_asn_line_location FOREIGN KEY(location_id) REFERENCES wh_record(id),
 CONSTRAINT fk_wh_asn_line_pool FOREIGN KEY(inventory_id) REFERENCES wh_inventory(id),
 CONSTRAINT fk_wh_asn_line_layer FOREIGN KEY(layer_id) REFERENCES wh_stock_layer(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
