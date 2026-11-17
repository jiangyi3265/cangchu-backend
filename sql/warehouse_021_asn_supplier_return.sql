-- Authorisation reserves custody; only evidenced physical handover removes stock.
ALTER TABLE wh_asn_custody
  ADD COLUMN returned_quantity BIGINT NOT NULL DEFAULT 0 AFTER accepted_quantity,
  DROP CHECK ck_asn_custody_quantity,
  ADD CONSTRAINT ck_asn_custody_quantity CHECK(quantity>0 AND quantity<=10000000 AND disposed_quantity>=0 AND disposed_quantity<=quantity AND accepted_quantity>=0 AND returned_quantity>=0 AND accepted_quantity+returned_quantity<=disposed_quantity AND approved_extra>=0 AND approved_extra<=quantity);

CREATE TABLE wh_asn_return_approval (
  id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
  custody_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  quantity BIGINT NOT NULL,
  handed_quantity BIGINT NOT NULL DEFAULT 0,
  cancelled_quantity BIGINT NOT NULL DEFAULT 0,
  recipient VARCHAR(200) NOT NULL,
  destination VARCHAR(1000) NOT NULL,
  reference VARCHAR(200) NOT NULL,
  reason VARCHAR(500) NOT NULL,
  actor_id BIGINT NOT NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  evidence_json LONGTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  CONSTRAINT fk_asn_return_approval_task FOREIGN KEY(custody_id) REFERENCES wh_asn_custody(id),
  CONSTRAINT ck_asn_return_approval_quantity CHECK(quantity>0 AND quantity<=10000000 AND handed_quantity>=0 AND cancelled_quantity>=0 AND handed_quantity+cancelled_quantity<=quantity),
  CONSTRAINT ck_asn_return_approval_evidence_json CHECK(JSON_VALID(evidence_json))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

ALTER TABLE wh_asn_custody_event
  ADD COLUMN return_approval_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
  ADD COLUMN handover_reference VARCHAR(200) NOT NULL DEFAULT '',
  ADD CONSTRAINT fk_asn_event_return_approval FOREIGN KEY(return_approval_id) REFERENCES wh_asn_return_approval(id),
  DROP CHECK ck_asn_custody_event_action,
  ADD CONSTRAINT ck_asn_custody_event_action CHECK(action_name IN('stage','inspect','approveOverage','putaway','scrap','approveReturn','cancelReturnApproval','returnSupplier') AND quantity>=0);
