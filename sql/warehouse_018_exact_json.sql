-- Apply with native writers stopped and a verified backup. MySQL 8.0.16+ enforces CHECK.
-- Preserve decimal JSON text exactly. Binary JSON parses fractional numbers as DOUBLE.
-- Existing values are copied as stored; previously lost digits cannot be recovered here.
-- Financial arithmetic stays in Java BigDecimal and relational DECIMAL columns.

ALTER TABLE wh_record
 MODIFY COLUMN business_json LONGTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
 ADD CONSTRAINT ck_record_business_json CHECK (business_json IS NULL OR JSON_VALID(business_json));

ALTER TABLE wh_audit
 MODIFY COLUMN before_json LONGTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
 ADD CONSTRAINT ck_audit_before_json CHECK (before_json IS NULL OR JSON_VALID(before_json)),
 MODIFY COLUMN after_json LONGTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
 ADD CONSTRAINT ck_audit_after_json CHECK (after_json IS NULL OR JSON_VALID(after_json));

ALTER TABLE wh_operation_request
 MODIFY COLUMN response_json LONGTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
 ADD CONSTRAINT ck_operation_request_response_json CHECK (response_json IS NULL OR JSON_VALID(response_json));

ALTER TABLE wh_order_billing
 MODIFY COLUMN source_snapshot LONGTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
 ADD CONSTRAINT ck_order_billing_source_snapshot CHECK (source_snapshot IS NULL OR JSON_VALID(source_snapshot)),
 MODIFY COLUMN quote_json LONGTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
 ADD CONSTRAINT ck_order_billing_quote_json CHECK (quote_json IS NULL OR JSON_VALID(quote_json));

ALTER TABLE wh_finance_period
 MODIFY COLUMN snapshot_json LONGTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
 ADD CONSTRAINT ck_finance_period_snapshot_json CHECK (snapshot_json IS NULL OR JSON_VALID(snapshot_json));

ALTER TABLE wh_finance_source
 MODIFY COLUMN evidence_json LONGTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
 ADD CONSTRAINT ck_finance_source_evidence_json CHECK (evidence_json IS NULL OR JSON_VALID(evidence_json));

ALTER TABLE wh_finance_invoice_line
 MODIFY COLUMN snapshot_json LONGTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
 ADD CONSTRAINT ck_finance_invoice_line_snapshot_json CHECK (snapshot_json IS NULL OR JSON_VALID(snapshot_json));

ALTER TABLE wh_stocktake_line
 MODIFY COLUMN result_json LONGTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
 ADD CONSTRAINT ck_stocktake_line_result_json CHECK (result_json IS NULL OR JSON_VALID(result_json));

ALTER TABLE wh_return_disposition
 MODIFY COLUMN result_json LONGTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
 ADD CONSTRAINT ck_return_disposition_result_json CHECK (result_json IS NULL OR JSON_VALID(result_json));

ALTER TABLE wh_storage_change
 MODIFY COLUMN payload_json LONGTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
 ADD CONSTRAINT ck_storage_change_payload_json CHECK (payload_json IS NULL OR JSON_VALID(payload_json));

ALTER TABLE wh_finance_adjustment_policy
 MODIFY COLUMN policy_json LONGTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
 ADD CONSTRAINT ck_finance_adjustment_policy_policy_json CHECK (policy_json IS NULL OR JSON_VALID(policy_json));

ALTER TABLE wh_finance_adjustment_approval
 MODIFY COLUMN plan_json LONGTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
 ADD CONSTRAINT ck_finance_adjustment_approval_plan_json CHECK (plan_json IS NULL OR JSON_VALID(plan_json)),
 MODIFY COLUMN input_json LONGTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
 ADD CONSTRAINT ck_finance_adjustment_approval_input_json CHECK (input_json IS NULL OR JSON_VALID(input_json));

ALTER TABLE wh_finance_adjustment_effect
 MODIFY COLUMN effect_json LONGTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
 ADD CONSTRAINT ck_finance_adjustment_effect_effect_json CHECK (effect_json IS NULL OR JSON_VALID(effect_json));
