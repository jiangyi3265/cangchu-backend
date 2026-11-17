package com.ruoyi.warehouse.data;

import com.ruoyi.warehouse.support.*;
import java.util.*;

/** Shared catalog assignments retain the name accepted on the original document. */
public final class SharedMasterReferences {
    private SharedMasterReferences() { }

    public static void bind(WarehouseRepository records, String kind, String key, String label,
                            Map<String,Object> data, Map<String,Object> before, boolean supplied) {
        String prior=before==null?"":WarehouseJson.text(before,key);
        String selected=supplied?WarehouseJson.text(data,key):prior;
        data.remove(key);
        if(selected.isEmpty()){data.remove(label);return;}
        if(selected.equals(prior)) {
            data.put(key,selected);data.put(label,before.get(label));return;
        }
        var target=records.scoped(kind,selected,true);
        if(!Boolean.TRUE.equals(target.get("active")))throw WarehouseException.conflict("Selected supplier or channel is inactive / 所选供应商或渠道已停用");
        data.put(key,selected);data.put(label,target.get("name"));
    }

    public static void reference(Map<String,String> refs,Map<String,Object> data,String key) {
        String value=WarehouseJson.text(data,key);if(!value.isEmpty())refs.put(key,value);
    }

    public static void protectChannelIdentity(WarehouseRepository records,Map<String,Object> before) {
        String id=WarehouseJson.text(before,"id"),name=WarehouseJson.text(before,"name");
        var references=records.jdbc().queryForList("SELECT owner_id FROM wh_reference WHERE target_id=? ORDER BY owner_id FOR UPDATE",String.class,id);
        var legacy=records.jdbc().queryForList("SELECT id FROM wh_record WHERE kind IN ('orders','channelTiers','charges') AND JSON_UNQUOTE(JSON_EXTRACT(business_json,'$.channel'))=? ORDER BY id FOR UPDATE",String.class,name);
        if(!references.isEmpty()||!legacy.isEmpty())throw WarehouseException.conflict("Channel name is used by orders or pricing; deactivate it instead / 渠道名称已用于订单或计费，请保留名称，可改为停用");
    }

    public static void channelPricing(WarehouseRepository records,Map<String,Object> data,Map<String,Object> before,Map<String,String> refs) {
        String name=WarehouseJson.text(data,"channel");if("Manual".equals(name))return;
        var ids=records.jdbc().queryForList("SELECT id FROM wh_record WHERE kind='channels' AND natural_key=? ORDER BY id FOR UPDATE",String.class,name.toLowerCase(Locale.ROOT));
        if(ids.size()!=1){if(before!=null&&name.equals(before.get("channel")))return;throw WarehouseException.invalid("Create the sales channel before its pricing rule / 请先创建销售渠道，再设置该渠道的收费规则");}
        var channel=before!=null&&name.equals(before.get("channel"))?records.get("channels",ids.get(0),true):records.scoped("channels",ids.get(0),true);
        if(!name.equals(channel.get("name")))throw WarehouseException.invalid("Use the exact channel name / 请使用准确的渠道名称");
        if(!Boolean.TRUE.equals(channel.get("active"))&&(before==null||!name.equals(before.get("channel"))))throw WarehouseException.conflict("Channel is inactive / 渠道已停用");
        refs.put("channelId",ids.get(0));
    }
}
