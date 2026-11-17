-- Stop warehouse writers and verify a backup before applying this migration.
CREATE TABLE wh_asn_late_link (
    child_asn VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    parent_asn VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    admin_policy VARCHAR(16) NOT NULL,
    sku_policy VARCHAR(16) NOT NULL,
    reason VARCHAR(500) NOT NULL,
    actor_id BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    evidence_json LONGTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    CONSTRAINT fk_asn_late_child FOREIGN KEY (child_asn) REFERENCES wh_record(id),
    CONSTRAINT fk_asn_late_parent FOREIGN KEY (parent_asn) REFERENCES wh_record(id),
    CONSTRAINT ck_asn_late_identity CHECK (child_asn <> parent_asn),
    CONSTRAINT ck_asn_late_policy CHECK (admin_policy IN ('included','separate') AND sku_policy IN ('group','separate')),
    CONSTRAINT ck_asn_late_link_evidence_json CHECK (JSON_VALID(evidence_json)),
    INDEX ix_asn_late_parent (parent_asn,child_asn)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
