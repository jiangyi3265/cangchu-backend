package com.ruoyi.warehouse.finance;

import com.ruoyi.warehouse.data.WarehouseRepository;
import com.ruoyi.warehouse.security.WarehouseScope;
import com.ruoyi.warehouse.support.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.ruoyi.warehouse.finance.AdjustmentSupport.*;

/** Warehouse exceptions create a queue item, never an automatic monetary reduction. */
@Service
public class FinanceReviewService {
    private final WarehouseRepository records;private final WarehouseScope scope;private final FinanceRuleService rules;private final FinanceWorkflowService workflow;private final FinanceAdjustmentService adjustments;private final JdbcTemplate jdbc;
    public FinanceReviewService(WarehouseRepository records,WarehouseScope scope,FinanceRuleService rules,FinanceWorkflowService workflow,FinanceAdjustmentService adjustments){this.records=records;this.scope=scope;this.rules=rules;this.workflow=workflow;this.adjustments=adjustments;jdbc=records.jdbc();}
    public static void enqueueReceiptCorrection(WarehouseRepository records,WarehouseScope scope,String correction,Map<String,Object> approval){
        var jdbc=records.jdbc();if(jdbc.queryForObject("SELECT COUNT(*) FROM wh_asn_correction WHERE id=? AND status='EXECUTED'",Long.class,correction)!=1)throw WarehouseException.conflict("Executed correction evidence required");
        String source="asn:"+approval.get("asn")+":"+approval.get("receipt");var fees=jdbc.queryForList("SELECT charge_id FROM wh_finance_source WHERE source_key=?",String.class,source);if(fees.size()!=1)throw WarehouseException.conflict("Original receipt fee is missing; reconcile first / 原收货费用缺失，请先核对");
        var charge=records.get("charges",fees.get(0),false);if(!approval.get("client").equals(charge.get("client"))||!approval.get("asn").equals(charge.get("asnId")))throw WarehouseException.conflict("Original receipt fee ownership mismatch");
        String id=UUID.randomUUID().toString();var input=map(approval.get("planInput"));var document=new LinkedHashMap<String,Object>();document.put("id",id);document.put("_version",1);document.put("status","OPEN");document.put("correction",correction);document.put("chargeId",fees.get(0));document.put("client",approval.get("client"));document.put("warehouse",approval.get("warehouse"));document.put("warehouseIds",approval.get("warehouseIds"));document.put("asn",approval.get("asn"));document.put("receiptLine",approval.get("receiptLine"));document.put("quantity",approval.get("quantity"));document.put("reason",input.get("reason"));document.put("evidenceReference",input.get("evidenceReference"));document.put("createdBy",scope.actor());document.put("createdAt",Instant.now().toString());
        jdbc.update("INSERT INTO wh_finance_review_case(id,correction_id,charge_id,client_id,warehouse_id,revision,status,document_json,document_hash) VALUES(?,?,?,?,?,1,'OPEN',?,?)",id,correction,fees.get(0),approval.get("client"),approval.get("warehouse"),WarehouseJson.write(document),hash(document));
        for(Object warehouse:(List<?>)approval.get("warehouseIds"))jdbc.update("INSERT INTO wh_finance_review_scope(case_id,warehouse_id) VALUES(?,?)",id,String.valueOf(warehouse));
        records.audit("QUEUE_RECEIPT_FINANCIAL_REVIEW","financeReviews",id,null,document);
    }
    @Transactional(readOnly=true) public Map<String,Object> list(int page,int size,String client,String status){
        rules.permission("query");if(page<1||page>100000||size<1||size>200||!Set.of("","OPEN","LINKED","RETAINED").contains(status))throw WarehouseException.invalid("Invalid review filters");
        var args=new ArrayList<Object>();String where="1=1";
        if(!scope.admin()){
            where="c.client_id IN (SELECT scope_id FROM wh_user_scope WHERE user_id=? AND scope_type='CLIENT') AND NOT EXISTS (SELECT 1 FROM wh_finance_review_scope s WHERE s.case_id=c.id AND s.warehouse_id NOT IN (SELECT scope_id FROM wh_user_scope WHERE user_id=? AND scope_type='WAREHOUSE'))";args.add(scope.actor());args.add(scope.actor());
            where+=" AND NOT EXISTS (SELECT 1 FROM wh_reference x JOIN wh_record target ON target.id=x.target_id WHERE (x.owner_id=c.charge_id OR x.owner_id IN (SELECT invoice_id FROM wh_finance_invoice_claim WHERE charge_id=c.charge_id)) AND (CASE WHEN target.kind='warehouses' THEN target.id ELSE target.warehouse_id END)<>'' AND (CASE WHEN target.kind='warehouses' THEN target.id ELSE target.warehouse_id END) NOT IN (SELECT scope_id FROM wh_user_scope WHERE user_id=? AND scope_type='WAREHOUSE'))";args.add(scope.actor());
        }
        if(client!=null&&!client.isBlank()){where+=" AND c.client_id=?";args.add(client);}if(!status.isEmpty()){where+=" AND c.status=?";args.add(status);}
        long total=jdbc.queryForObject("SELECT COUNT(*) FROM wh_finance_review_case c WHERE "+where,Long.class,args.toArray());args.add(size);args.add((long)(page-1)*size);var rows=new ArrayList<Map<String,Object>>();for(String id:jdbc.queryForList("SELECT c.id FROM wh_finance_review_case c WHERE "+where+" ORDER BY c.created_at DESC,c.id LIMIT ? OFFSET ?",String.class,args.toArray())){var row=read(id,false);access(row);rows.add(row);}return Map.of("code",200,"msg","success","total",total,"rows",rows);
    }
    @Transactional public Map<String,Object> get(String id){rules.permission("query");var row=read(id,false);access(row);var result=new LinkedHashMap<>(row);result.put("source",adjustments.source(text(row,"chargeId")));return result;}
    @Transactional public Map<String,Object> action(String id,Map<String,Object> input){
        rules.permission("edit");FinanceInput.keys(input,"requestId","_version","action","reason");String action=FinanceInput.option(input,"action","","adjust","retain"),reason=FinanceInput.text(input,"reason",500,true),request=FinanceInput.id(input,"requestId");var visible=read(id,false);access(visible);records.scoped("clients",text(visible,"client"),true);
        String digest=hash(Map.of("id",id,"input",input));jdbc.update("INSERT INTO wh_operation_request(actor_id,request_id,operation_type,request_hash,response_json) VALUES(?,?,?,?,?) ON DUPLICATE KEY UPDATE request_id=request_id",scope.actor(),request,"FINANCE_REVIEW_ACTION",digest,"{}");
        var previous=jdbc.queryForMap("SELECT operation_type,request_hash,response_json FROM wh_operation_request WHERE actor_id=? AND request_id=? FOR UPDATE",scope.actor(),request);if(!"FINANCE_REVIEW_ACTION".equals(previous.get("operation_type"))||!digest.equals(previous.get("request_hash")))throw WarehouseException.conflict("Request identity already used");var replay=WarehouseJson.map(String.valueOf(previous.get("response_json")));if(!replay.isEmpty()){access(replay);return replay;}
        var old=read(id,true);version(old,input);if(!"OPEN".equals(old.get("status")))throw WarehouseException.conflict("Review case has already been handled / 此待办已处理");var data=WarehouseJson.copy(old);
        if("adjust".equals(action)){
            String nonce=UUID.nameUUIDFromBytes((id+":"+request).getBytes(StandardCharsets.UTF_8)).toString();var adjustment=adjustments.create(Map.of("requestId",nonce,"chargeId",old.get("chargeId"),"reason",reason,"evidenceRef","Receipt correction "+old.get("correction")+"; "+old.get("evidenceReference")));data.put("adjustment",adjustment.get("id"));data.put("status","LINKED");
        }else data.put("status","RETAINED");
        data.put("_version",((Number)old.get("_version")).longValue()+1);data.put("decisionReason",reason);data.put("handledBy",scope.actor());data.put("handledAt",Instant.now().toString());
        if(jdbc.update("UPDATE wh_finance_review_case SET revision=?,status=?,document_json=?,document_hash=? WHERE id=? AND revision=?",data.get("_version"),data.get("status"),WarehouseJson.write(data),hash(data),id,old.get("_version"))!=1)throw WarehouseException.conflict("Review case changed");records.audit("HANDLE_RECEIPT_FINANCIAL_REVIEW","financeReviews",id,old,data);
        jdbc.update("UPDATE wh_operation_request SET response_json=? WHERE actor_id=? AND request_id=? AND request_hash=?",WarehouseJson.write(data),scope.actor(),request,digest);return data;
    }
    private Map<String,Object> read(String id,boolean lock){var rows=jdbc.query("SELECT * FROM wh_finance_review_case WHERE id=?"+(lock?" FOR UPDATE":""),(rs,n)->{var row=WarehouseJson.map(rs.getString("document_json"));if(!hash(row).equals(rs.getString("document_hash"))||!id.equals(row.get("id"))||!rs.getString("client_id").equals(row.get("client"))||!rs.getString("warehouse_id").equals(row.get("warehouse"))||!rs.getString("charge_id").equals(row.get("chargeId"))||!rs.getString("correction_id").equals(row.get("correction"))||!rs.getString("status").equals(row.get("status"))||rs.getLong("revision")!=((Number)row.get("_version")).longValue())throw WarehouseException.conflict("Review case evidence mismatch / 财务待办证据不一致");return row;},id);if(rows.size()!=1)throw WarehouseException.missing();return rows.get(0);}
    private void access(Map<String,Object> row){records.scoped("clients",text(row,"client"),false);for(Object warehouse:(List<?>)row.get("warehouseIds"))if(!scope.granted("WAREHOUSE",String.valueOf(warehouse)))throw WarehouseException.forbidden();var charge=records.get("charges",text(row,"chargeId"),false);workflow.assertAggregate("charges",charge);if(!WarehouseJson.empty(charge.get("invoice")))workflow.assertAggregate("invoices",records.get("invoices",text(charge,"invoice"),false));}
}
