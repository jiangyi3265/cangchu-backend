package com.ruoyi.warehouse.controller;

import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.warehouse.data.WarehouseRepository;
import com.ruoyi.warehouse.security.WarehouseScope;
import com.ruoyi.warehouse.support.WarehouseException;
import com.ruoyi.warehouse.support.WarehouseJson;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/warehouse/security/scopes")
public class WarehouseScopeController {
    private final WarehouseRepository records;private final WarehouseScope scope;
    public WarehouseScopeController(WarehouseRepository records,WarehouseScope scope){this.records=records;this.scope=scope;}
    @GetMapping("/{userId}") @PreAuthorize("@ss.hasPermi('warehouse:scope:query')")
    public AjaxResult get(@PathVariable long userId){scope.requireAdministrator();userExists(userId);return AjaxResult.success(read(userId));}
    @PutMapping("/{userId}") @PreAuthorize("@ss.hasPermi('warehouse:scope:edit')")
    @Transactional
    public AjaxResult save(@PathVariable long userId,@RequestBody Map<String,Object> body){
        scope.requireAdministrator();if(userId==1)throw WarehouseException.invalid("The built-in super administrator has unrestricted scope");
        if(userId<=0||records.jdbc().queryForList("SELECT user_id FROM sys_user WHERE user_id=? AND del_flag='0' FOR UPDATE",Long.class,userId).isEmpty())throw WarehouseException.missing();
        if(!Set.of("clients","warehouses","_version").equals(body.keySet()))throw WarehouseException.invalid("Provide both scopes and the version returned by GET");
        var previous=read(userId);if(!Objects.equals(body.get("_version"),previous.get("_version")))throw WarehouseException.conflict("Scopes changed; reload before saving / 数据范围已变更，请刷新后保存");
        var chosen=new LinkedHashMap<String,List<String>>();for(String kind:List.of("clients","warehouses")){
            if(!(body.get(kind) instanceof List<?> ids)||ids.size()>5000)throw WarehouseException.invalid("Scope must be a bounded list");
            var unique=new TreeSet<String>();for(Object id:ids){if(!(id instanceof String s)||s.isBlank()||s.length()>64||!unique.add(s))throw WarehouseException.invalid("Invalid or duplicate scope identifier");records.get(kind,s,true);}chosen.put(kind,new ArrayList<>(unique));
        }
        records.jdbc().update("DELETE FROM wh_user_scope WHERE user_id=?",userId);
        for(var entry:chosen.entrySet())for(String id:entry.getValue())records.jdbc().update("INSERT INTO wh_user_scope(user_id,scope_type,scope_id) VALUES(?,?,?)",userId,"clients".equals(entry.getKey())?"CLIENT":"WAREHOUSE",id);
        var result=read(userId);records.audit("SCOPES_UPDATED","users",Long.toString(userId),previous,result);return AjaxResult.success(result);
    }
    private void userExists(long id){if(id<=0||records.jdbc().queryForObject("SELECT COUNT(*) FROM sys_user WHERE user_id=? AND del_flag='0'",Long.class,id)!=1)throw WarehouseException.missing();}
    private Map<String,Object> read(long user){
        var result=new LinkedHashMap<String,Object>();result.put("clients",records.jdbc().queryForList("SELECT scope_id FROM wh_user_scope WHERE user_id=? AND scope_type='CLIENT' ORDER BY scope_id",String.class,user));result.put("warehouses",records.jdbc().queryForList("SELECT scope_id FROM wh_user_scope WHERE user_id=? AND scope_type='WAREHOUSE' ORDER BY scope_id",String.class,user));
        try{result.put("_version",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(WarehouseJson.write(result).getBytes(StandardCharsets.UTF_8))));}catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}return result;
    }
}
