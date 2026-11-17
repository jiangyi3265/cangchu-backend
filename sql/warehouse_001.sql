-- Warehouse domain schema. Apply to a separate RuoYi database during migration.
-- No legacy or live database is modified by application startup.
CREATE TABLE IF NOT EXISTS wh_record (
 id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 kind VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 client_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT '',
 warehouse_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT '',
 scope_key VARCHAR(140) NOT NULL DEFAULT '',
 natural_key VARCHAR(191) COLLATE utf8mb4_bin NOT NULL,
 display_name VARCHAR(255) NOT NULL,
 business_json JSON NOT NULL,
 revision BIGINT NOT NULL DEFAULT 1,
 created_by BIGINT NOT NULL,
 updated_by BIGINT NOT NULL,
 created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 UNIQUE KEY uk_wh_record_natural(kind,scope_key,natural_key),
 KEY ix_wh_record_scope(kind,client_id,warehouse_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE IF NOT EXISTS wh_reference (
 owner_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 target_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 field_name VARCHAR(64) NOT NULL,
 PRIMARY KEY(owner_id,target_id,field_name),
 CONSTRAINT fk_wh_ref_owner FOREIGN KEY(owner_id) REFERENCES wh_record(id) ON DELETE CASCADE,
 CONSTRAINT fk_wh_ref_target FOREIGN KEY(target_id) REFERENCES wh_record(id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE IF NOT EXISTS wh_user_scope (
 user_id BIGINT NOT NULL,
 scope_type VARCHAR(16) NOT NULL,
 scope_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 PRIMARY KEY(user_id,scope_type,scope_id),
 CONSTRAINT fk_wh_scope_record FOREIGN KEY(scope_id) REFERENCES wh_record(id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE IF NOT EXISTS wh_audit (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 actor_id BIGINT NOT NULL,
 action_name VARCHAR(80) NOT NULL,
 record_id VARCHAR(64) NOT NULL,
 record_kind VARCHAR(64) NOT NULL,
 before_json JSON NULL,
 after_json JSON NULL,
 occurred_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 KEY ix_wh_audit_record(record_kind,record_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE IF NOT EXISTS wh_inventory (
 id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 client_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 product_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 warehouse_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 location_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 batch_number VARCHAR(191) NOT NULL DEFAULT '',
 serial_number VARCHAR(191) NOT NULL DEFAULT '',
 expiry_date VARCHAR(10) NOT NULL DEFAULT '',
 on_hand BIGINT NOT NULL DEFAULT 0,
 reserved BIGINT NOT NULL DEFAULT 0,
 revision BIGINT NOT NULL DEFAULT 1,
 CONSTRAINT ck_wh_inventory_nonnegative CHECK(on_hand >= 0 AND reserved >= 0 AND reserved <= on_hand),
 CONSTRAINT fk_wh_inventory_client FOREIGN KEY(client_id) REFERENCES wh_record(id),
 CONSTRAINT fk_wh_inventory_product FOREIGN KEY(product_id) REFERENCES wh_record(id),
 CONSTRAINT fk_wh_inventory_warehouse FOREIGN KEY(warehouse_id) REFERENCES wh_record(id),
 CONSTRAINT fk_wh_inventory_location FOREIGN KEY(location_id) REFERENCES wh_record(id),
 UNIQUE KEY uk_wh_inventory_pool(product_id,warehouse_id,location_id,batch_number,serial_number,expiry_date),
 KEY ix_wh_inventory_scope(client_id,warehouse_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE IF NOT EXISTS wh_stock_layer (
 id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 inventory_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 origin_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 received_at TIMESTAMP(6) NOT NULL,
 remaining_quantity BIGINT NOT NULL,
 reserved_quantity BIGINT NOT NULL DEFAULT 0,
 CONSTRAINT ck_wh_layer_quantity CHECK(remaining_quantity >= 0 AND reserved_quantity >= 0 AND reserved_quantity <= remaining_quantity),
 CONSTRAINT fk_wh_layer_inventory FOREIGN KEY(inventory_id) REFERENCES wh_inventory(id),
 KEY ix_wh_layer_inventory(inventory_id,received_at,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE IF NOT EXISTS wh_ledger (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 operation_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 inventory_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 layer_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 quantity_delta BIGINT NOT NULL,
 reserved_delta BIGINT NOT NULL DEFAULT 0,
 reference_id VARCHAR(64) NOT NULL DEFAULT '',
 reason VARCHAR(500) NOT NULL,
 actor_id BIGINT NOT NULL,
 occurred_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 CONSTRAINT fk_wh_ledger_inventory FOREIGN KEY(inventory_id) REFERENCES wh_inventory(id),
 CONSTRAINT fk_wh_ledger_layer FOREIGN KEY(layer_id) REFERENCES wh_stock_layer(id),
 KEY ix_wh_ledger_operation(operation_id),
 KEY ix_wh_ledger_reference(reference_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE IF NOT EXISTS wh_operation_request (
 actor_id BIGINT NOT NULL,
 request_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 operation_type VARCHAR(64) NOT NULL,
 request_hash CHAR(64) CHARACTER SET ascii NOT NULL,
 response_json JSON NOT NULL,
 created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 PRIMARY KEY(actor_id,request_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
