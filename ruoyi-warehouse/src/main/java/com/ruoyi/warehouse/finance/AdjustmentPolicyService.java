package com.ruoyi.warehouse.finance;

import com.ruoyi.warehouse.data.WarehouseRepository;
import com.ruoyi.warehouse.security.WarehouseScope;
import com.ruoyi.warehouse.support.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.ruoyi.warehouse.finance.AdjustmentSupport.*;

@Service
public class AdjustmentPolicyService {
    private final WarehouseRepository records;private final WarehouseScope scope;
    public AdjustmentPolicyService(WarehouseRepository records,WarehouseScope scope){this.records=records;this.scope=scope;}
    Map<String,Object> current(){return records.jdbc().queryForObject("SELECT revision,policy_json FROM wh_finance_adjustment_policy WHERE id=1",(rs,n)->{var out=new LinkedHashMap<String,Object>();out.put("_version",rs.getLong(1));out.put("policy",rs.getString(2)==null?null:WarehouseJson.map(rs.getString(2)));return out;});}
    Map<String,Object> lockedCurrent(){return records.jdbc().queryForObject("SELECT revision,policy_json FROM wh_finance_adjustment_policy WHERE id=1 FOR UPDATE",(rs,n)->{var out=new LinkedHashMap<String,Object>();out.put("_version",rs.getLong(1));out.put("policy",rs.getString(2)==null?null:WarehouseJson.map(rs.getString(2)));return out;});}
    public Map<String,Object> get(){scope.requireAdministrator();return current();}
    @Transactional public Map<String,Object> save(Map<String,Object> input){
        scope.requireAdministrator();FinanceInput.keys(input,"requestId","_version","policy");String request=FinanceInput.id(input,"requestId");var policy=map(input.get("policy"));validate(policy);
        records.jdbc().queryForObject("SELECT revision FROM wh_finance_adjustment_policy WHERE id=1 FOR UPDATE",Long.class);String digest=hash(input);
        records.jdbc().update("INSERT INTO wh_operation_request(actor_id,request_id,operation_type,request_hash,response_json) VALUES(?,?,?,?,?) ON DUPLICATE KEY UPDATE request_id=request_id",scope.actor(),request,"ADJUSTMENT_POLICY",digest,"{}");
        var previous=records.jdbc().queryForMap("SELECT operation_type,request_hash,response_json FROM wh_operation_request WHERE actor_id=? AND request_id=? FOR UPDATE",scope.actor(),request);if(!"ADJUSTMENT_POLICY".equals(previous.get("operation_type"))||!digest.equals(previous.get("request_hash")))throw WarehouseException.conflict("Request identity already used");var replay=WarehouseJson.map(String.valueOf(previous.get("response_json")));if(!replay.isEmpty())return replay;
        var old=current();long version=WarehouseJson.integer(input.get("_version"),"_version",0,Long.MAX_VALUE);if(((Number)old.get("_version")).longValue()!=version)throw WarehouseException.conflict("Policy changed; reload / 政策已改变，请刷新");
        if(old.get("policy") instanceof Map<?,?> prior&&Objects.equals(prior.get("version"),policy.get("version"))&&!hash(prior).equals(hash(policy)))throw WarehouseException.conflict("Changed policy requires a new version name / 修改政策必须更换版本名称");
        for(String client:map(policy.get("buyers")).keySet())records.scoped("clients",client,false);
        records.jdbc().update("UPDATE wh_finance_adjustment_policy SET revision=revision+1,policy_json=? WHERE id=1",WarehouseJson.write(policy));var saved=current();records.audit("CONFIGURE_ADJUSTMENT_POLICY","adjustmentPolicy","policy",old,saved);records.jdbc().update("UPDATE wh_operation_request SET response_json=? WHERE actor_id=? AND request_id=?",WarehouseJson.write(saved),scope.actor(),request);return saved;
    }
    private static void validate(Map<String,Object> policy){
        FinanceInput.keys(policy,"version","taxMethod","requireDifferentActor","seller","buyers","creditPrefix");FinanceInput.text(policy,"version",100,true);if(!"original-line-remainder-half-even".equals(policy.get("taxMethod")))throw WarehouseException.invalid("Unsupported tax calculation policy");if(!(policy.get("requireDifferentActor") instanceof Boolean))throw WarehouseException.invalid("Explicit separation-of-duties policy required / 请明确审批与执行是否分人");
        String prefix=FinanceInput.text(policy,"creditPrefix",20,true);if(!prefix.matches("[A-Z][A-Z0-9-]{0,19}"))throw WarehouseException.invalid("Credit prefix must be uppercase letters, digits or hyphens");profile(map(policy.get("seller")));
        if(!(policy.get("buyers") instanceof Map<?,?> buyers)||buyers.size()>10000)throw WarehouseException.invalid("Buyer profiles are required");for(var entry:map(policy.get("buyers")).entrySet()){FinanceInput.id(Map.of("client",entry.getKey()),"client");profile(map(entry.getValue()));}
    }
    private static void profile(Map<String,Object> row){FinanceInput.keys(row,"name","address","taxId");FinanceInput.text(row,"name",200,true);FinanceInput.text(row,"address",1000,true);FinanceInput.text(row,"taxId",100,true);}
}
