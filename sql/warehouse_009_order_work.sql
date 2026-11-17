-- Scan evidence and sealed packages for native manual orders.
-- Apply after warehouse_003_orders.sql. Scans/packing never change on-hand stock.
CREATE TABLE IF NOT EXISTS wh_order_scan (
 id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 order_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 allocation_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 barcode VARCHAR(191) NOT NULL,
 serial_number VARCHAR(191) NOT NULL DEFAULT '',
 state VARCHAR(16) NOT NULL,
 actor_id BIGINT NOT NULL,
 created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 voided_by BIGINT NULL,
 voided_at TIMESTAMP(6) NULL,
 void_reason VARCHAR(500) NOT NULL DEFAULT '',
 CONSTRAINT ck_wh_scan_state CHECK(state IN ('ACTIVE','VOID')),
 CONSTRAINT fk_wh_scan_order FOREIGN KEY(order_id) REFERENCES wh_record(id),
 CONSTRAINT fk_wh_scan_allocation FOREIGN KEY(allocation_id) REFERENCES wh_order_allocation(id),
 KEY ix_wh_scan_order(order_id,created_at,id),
 KEY ix_wh_scan_allocation(allocation_id,state)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE IF NOT EXISTS wh_order_package (
 id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 order_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 package_code VARCHAR(100) NOT NULL,
 state VARCHAR(16) NOT NULL,
 weight_kg DECIMAL(12,3) NOT NULL,
 length_cm DECIMAL(10,2) NOT NULL,
 width_cm DECIMAL(10,2) NOT NULL,
 height_cm DECIMAL(10,2) NOT NULL,
 notes VARCHAR(500) NOT NULL DEFAULT '',
 actor_id BIGINT NOT NULL,
 created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 voided_by BIGINT NULL,
 voided_at TIMESTAMP(6) NULL,
 void_reason VARCHAR(500) NOT NULL DEFAULT '',
 CONSTRAINT ck_wh_package_state CHECK(state IN ('SEALED','VOID','DESPATCHED')),
 CONSTRAINT ck_wh_package_measurements CHECK(weight_kg>0 AND length_cm>0 AND width_cm>0 AND height_cm>0),
 CONSTRAINT fk_wh_package_order FOREIGN KEY(order_id) REFERENCES wh_record(id),
 UNIQUE KEY uk_wh_package_code(order_id,package_code),
 KEY ix_wh_package_order(order_id,state)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE IF NOT EXISTS wh_order_package_line (
 package_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 allocation_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 quantity BIGINT NOT NULL,
 PRIMARY KEY(package_id,allocation_id),
 CONSTRAINT ck_wh_package_line_quantity CHECK(quantity>0),
 CONSTRAINT fk_wh_package_line_package FOREIGN KEY(package_id) REFERENCES wh_order_package(id),
 CONSTRAINT fk_wh_package_line_allocation FOREIGN KEY(allocation_id) REFERENCES wh_order_allocation(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
