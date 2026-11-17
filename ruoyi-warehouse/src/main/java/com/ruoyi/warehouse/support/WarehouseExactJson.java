package com.ruoyi.warehouse.support;

import java.util.*;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Fail startup before accepting native financial writes against lossy JSON storage. */
@Component
public class WarehouseExactJson implements InitializingBean {
    private final JdbcTemplate jdbc;
    private static final List<String> FIELDS=List.of(
        "wh_carrier_connection.config_json","wh_carrier_shipment.snapshot_json","wh_carrier_shipment.config_json","wh_carrier_shipment.identity_json",
        "wh_integration_order.evidence_json","wh_integration_connection.cursor_json","wh_integration_job.input_json","wh_integration_job.window_json","wh_integration_job.result_json","wh_integration_inbox.document_json","wh_integration_event.document_json",
        "wh_master_import.document_json","wh_asn_correction.approval_json","wh_asn_correction.result_json","wh_finance_review_case.document_json","wh_storage_identity_review.document_json","wh_storage_identity_event.document_json",
        "wh_asn_identity_approval.evidence_json","wh_asn_unidentified_event.result_json","wh_asn_late_link.evidence_json","wh_asn_return_approval.evidence_json","wh_asn_custody_event.result_json","wh_record.business_json","wh_audit.before_json","wh_audit.after_json","wh_operation_request.response_json",
        "wh_order_billing.source_snapshot","wh_order_billing.quote_json","wh_finance_period.snapshot_json",
        "wh_finance_source.evidence_json","wh_finance_invoice_line.snapshot_json","wh_stocktake_line.result_json",
        "wh_return_disposition.result_json","wh_storage_change.payload_json","wh_finance_adjustment_policy.policy_json",
        "wh_finance_adjustment_approval.plan_json","wh_finance_adjustment_approval.input_json","wh_finance_adjustment_effect.effect_json");
    public WarehouseExactJson(JdbcTemplate jdbc){this.jdbc=jdbc;}
    @Override public void afterPropertiesSet(){
        var columns=jdbc.queryForList("SELECT TABLE_NAME,COLUMN_NAME,DATA_TYPE FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND LEFT(TABLE_NAME,3)='wh_'");
        var exact=new HashSet<String>();var present=new HashSet<String>();
        for(var column:columns){String field=column.get("TABLE_NAME")+"."+column.get("COLUMN_NAME");present.add(field);String type=String.valueOf(column.get("DATA_TYPE"));if("json".equalsIgnoreCase(type))throw migrationRequired();if("longtext".equalsIgnoreCase(type))exact.add(field);}
        if(!exact.containsAll(FIELDS))throw migrationRequired();
        // Migration 021 has several atomic DDL statements. Its final ALTER must
        // exist as well as the approval table before native supplier writes start.
        if(!present.containsAll(List.of("wh_asn_custody.returned_quantity","wh_asn_custody_event.return_approval_id","wh_asn_custody_event.handover_reference")))throw migrationRequired();
        if(!present.containsAll(List.of("wh_unidentified_occupancy.quantity","wh_asn_unidentified.mapped_quantity","wh_asn_unidentified.returned_quantity","wh_asn_unidentified.scrapped_quantity","wh_asn_identity_approval.executed_quantity","wh_asn_identity_approval.cancelled_quantity")))throw migrationRequired();
        if(!present.containsAll(List.of("wh_finance_review_scope.case_id","wh_finance_review_scope.warehouse_id")))throw migrationRequired();
        if(!present.containsAll(List.of("wh_carrier_closed_request.resource_id","wh_carrier_label.sha256")))throw migrationRequired();
        var constraints=new HashSet<String>();
        for(var row:jdbc.queryForList("SELECT CONSTRAINT_NAME,ENFORCED FROM information_schema.TABLE_CONSTRAINTS WHERE TABLE_SCHEMA=DATABASE() AND CONSTRAINT_TYPE='CHECK' AND LEFT(TABLE_NAME,3)='wh_'"))if("YES".equals(row.get("ENFORCED")))constraints.add(String.valueOf(row.get("CONSTRAINT_NAME")));
        for(String field:FIELDS)if(!constraints.contains("ck_"+field.substring(3).replace('.','_')))throw migrationRequired();
        if(!constraints.containsAll(List.of("ck_unidentified_occupancy_quantity","ck_asn_unidentified_quantity","ck_identity_approval_quantity","ck_identity_approval_decision","ck_unidentified_event_action","ck_carrier_closed_kind","ck_carrier_connection_state","ck_carrier_shipment_state","ck_carrier_label_position")))throw migrationRequired();
        if(!constraints.containsAll(List.of("ck_storage_identity_period","ck_storage_identity_event_action","ck_asn_correction_state","ck_finance_review_state","ck_master_import_state","ck_integration_connection_state","ck_integration_job_state","ck_integration_inbox_state","ck_integration_authority_state","ck_integration_mapping_state")))throw migrationRequired();
    }
    private static IllegalStateException migrationRequired(){return new IllegalStateException("Exact warehouse JSON storage is required. Stop native writers, verify a backup and apply warehouse migrations through warehouse_039_carriers_menu.sql (including warehouse_018_exact_json.sql) on MySQL 8.0.16+ before starting this version.");}
}
