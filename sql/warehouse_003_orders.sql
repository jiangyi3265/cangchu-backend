-- Native manual order reservations and immutable despatch pricing evidence.
-- Apply only after warehouse_001.sql to the separate Java database.
CREATE TABLE IF NOT EXISTS wh_order_allocation (
 id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 order_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 inventory_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 layer_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 quantity BIGINT NOT NULL,
 state VARCHAR(16) NOT NULL,
 created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 CONSTRAINT ck_wh_order_allocation_quantity CHECK(quantity>0),
 CONSTRAINT ck_wh_order_allocation_state CHECK(state IN ('RESERVED','RELEASED','CONSUMED')),
 CONSTRAINT fk_wh_allocation_order FOREIGN KEY(order_id) REFERENCES wh_record(id),
 CONSTRAINT fk_wh_allocation_inventory FOREIGN KEY(inventory_id) REFERENCES wh_inventory(id),
 CONSTRAINT fk_wh_allocation_layer FOREIGN KEY(layer_id) REFERENCES wh_stock_layer(id),
 UNIQUE KEY uk_wh_allocation_layer(order_id,layer_id),
 KEY ix_wh_allocation_pool(inventory_id,state),
 KEY ix_wh_allocation_layer(layer_id,state)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE IF NOT EXISTS wh_order_billing (
 order_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 charge_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
 source_snapshot JSON NOT NULL,
 quote_json JSON NOT NULL,
 created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 CONSTRAINT fk_wh_order_billing_order FOREIGN KEY(order_id) REFERENCES wh_record(id),
 CONSTRAINT fk_wh_order_billing_charge FOREIGN KEY(charge_id) REFERENCES wh_record(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
