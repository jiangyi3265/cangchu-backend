package com.ruoyi.warehouse.integrations;

import com.ruoyi.warehouse.orders.OrderService;
import com.ruoyi.warehouse.support.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static com.ruoyi.warehouse.integrations.IntegrationIntakeSupport.*;

@Service
public class IntegrationAcceptanceService {
    private final IntegrationIntakeSupport s;
    private final IntegrationJobLock locks;
    private final OrderService orders;
    private final TransactionTemplate tx;
    public IntegrationAcceptanceService(IntegrationIntakeSupport s,IntegrationJobLock locks,OrderService orders,PlatformTransactionManager manager){this.s=s;this.locks=locks;this.orders=orders;tx=new TransactionTemplate(manager);}

    public Map<String,Object> settings(String id,String item) {
        s.connection(id,false);
        var args=new ArrayList<Object>();args.add(id);String clause="";
        if(item!=null&&!item.isBlank()) {
            var doc=WarehouseJson.map((String)s.inbox(id,item,false).get("document_json"));var pairs=new ArrayList<String>();
            for(var line:WarehouseJson.maps(doc.get("lines"))){pairs.add("(external_product_id=? AND external_variation_id=?)");args.add(line.get("productId"));args.add(line.get("variationId"));}
            clause=pairs.isEmpty()?" AND 1=0":" AND ("+String.join(" OR ",pairs)+")";
        }
        var mappings=s.jdbc.queryForList("SELECT * FROM wh_integration_mapping WHERE connection_id=?"+clause+" ORDER BY updated_at DESC,id LIMIT 200",args.toArray());
        mappings.forEach(row->{row.put("active",flag(row.get("active")));row.put("updated_at",IntegrationService.utc(row.get("updated_at")));});
        List<Map<String,Object>> acceptedLines=List.of();
        if(item!=null&&!item.isBlank()) {
            var accepted=s.jdbc.queryForList("SELECT evidence_json FROM wh_integration_order WHERE inbox_id=?",item);
            if(!accepted.isEmpty())acceptedLines=WarehouseJson.maps(WarehouseJson.map((String)accepted.get(0).get("evidence_json")).get("lines"));
        }
        return Map.of("authority",s.authority(id,false),"mappings",mappings,"acceptedLines",acceptedLines);
    }
    public Map<String,Object> authority(String id,Map<String,Object> input) {
        s.scope.requireAdministrator();keys(input,Set.of("requestId","_version","sourceIdentity","cutoverAt","fulfillmentMode","active","reason","exclusiveSource"));
        String request=text(input,"requestId",64),hash=digest(id,input);
        try(var lease=locks.acquire(id)){return tx.execute(status->{
            var connection=s.connection(id,true);s.permission("warehouse:integrations:authority",connection);
            var replay=s.replay(request,"INTEGRATION_AUTHORITY",hash,true);if(replay!=null)return replay;
            var before=s.authority(id,true);long expected=version(input.get("_version"));
            if(expected!=(before.isEmpty()?0:version(before.get("revision"))))throw WarehouseException.conflict("Source policy changed / 接单来源设置已更新");
            String identity=s.transport.canonicalSite(text(input,"sourceIdentity",512)),mode=text(input,"fulfillmentMode",32),reason=text(input,"reason",500);
            if(!Set.of("CORE","LEGACY_METADATA").contains(mode)||!(input.get("active") instanceof Boolean active))throw WarehouseException.invalid("Invalid source policy");
            if(active&&!Boolean.TRUE.equals(input.get("exclusiveSource")))throw WarehouseException.invalid("Confirm the source has one warehouse writer / 请确认原系统已停止该来源接单，且没有其他接单通道");
            Instant cutover;try{cutover=Instant.parse(text(input,"cutoverAt",40));}catch(Exception e){throw WarehouseException.invalid("Use an ISO UTC cutover time");}
            if(cutover.isBefore(Instant.parse("2000-01-01T00:00:00Z"))||cutover.isAfter(Instant.parse("2100-01-01T00:00:00Z")))throw WarehouseException.invalid("Invalid cutover time");
            if(!before.isEmpty()&&(!identity.equals(before.get("source_identity"))||!cutover.equals(Instant.parse((String)before.get("cutover_at")))))throw WarehouseException.conflict("Source identity and cutover are fixed; plan an audited migration to change them / 来源标识及接单起点已固定，变更需核对迁移方案");
            if(active)s.enabled(connection);
            String key=IntegrationService.hash("store:"+identity);
            if(before.isEmpty())s.jdbc.update("INSERT INTO wh_integration_authority(connection_id,source_key,source_identity,cutover_at,fulfillment_mode,active,reason,updated_by) VALUES(?,?,?,?,?,?,?,?)",id,key,identity,Timestamp.from(cutover),mode,active,reason,s.scope.actor());
            else s.jdbc.update("UPDATE wh_integration_authority SET fulfillment_mode=?,active=?,reason=?,revision=revision+1,updated_by=?,updated_at=CURRENT_TIMESTAMP(6) WHERE connection_id=?",mode,active,reason,s.scope.actor(),id);
            var after=s.authority(id,false);s.records.audit("INTEGRATION_AUTHORITY","integrationConnections",id,before,after);return s.complete(request,after);
        });}
    }
    public Map<String,Object> mapping(String id,Map<String,Object> input) {
        s.scope.requireAdministrator();keys(input,Set.of("requestId","_version","productId","variationId","sku","product","active"));
        String request=text(input,"requestId",64),hash=digest(id,input);
        try(var lease=locks.acquire(id)){return tx.execute(status->{
            var connection=s.connection(id,true);s.permission("warehouse:integrations:mapping",connection);
            var replay=s.replay(request,"INTEGRATION_MAPPING",hash,true);if(replay!=null)return replay;
            String product=text(input,"productId",19),variation=text(input,"variationId",19),sku=text(input,"sku",191),local=text(input,"product",64);
            if(!product.matches("[1-9][0-9]{0,18}")||!variation.matches("0|[1-9][0-9]{0,18}")||!(input.get("active") instanceof Boolean active))throw WarehouseException.invalid("Invalid product mapping");
            String key=IntegrationService.hash(id+":"+product+":"+variation);
            var rows=s.jdbc.queryForList("SELECT * FROM wh_integration_mapping WHERE id=? FOR UPDATE",key);var before=rows.isEmpty()?null:rows.get(0);
            if(before!=null){before.put("active",flag(before.get("active")));before.put("updated_at",IntegrationService.utc(before.get("updated_at")));}
            if(version(input.get("_version"))!=(before==null?0:version(before.get("revision"))))throw WarehouseException.conflict("Product mapping changed / 商品对照已更新");
            if(active)s.product(local,(String)connection.get("client_id"),true);
            else {var p=s.records.scoped("products",local,true);if(!Objects.equals(p.get("client"),connection.get("client_id")))throw new IntegrationFailure("MAPPING_CLIENT_MISMATCH");}
            if(before==null)s.jdbc.update("INSERT INTO wh_integration_mapping(id,connection_id,external_product_id,external_variation_id,external_sku,product_id,active,updated_by) VALUES(?,?,?,?,?,?,?,?)",key,id,product,variation,sku,local,active,s.scope.actor());
            else s.jdbc.update("UPDATE wh_integration_mapping SET external_sku=?,product_id=?,active=?,revision=revision+1,updated_by=?,updated_at=CURRENT_TIMESTAMP(6) WHERE id=?",sku,local,active,s.scope.actor(),key);
            var after=s.jdbc.queryForMap("SELECT * FROM wh_integration_mapping WHERE id=?",key);after.put("active",flag(after.get("active")));after.put("updated_at",IntegrationService.utc(after.get("updated_at")));
            s.records.audit("INTEGRATION_MAPPING","integrationMappings",key,before,after);return s.complete(request,after);
        });}
    }
    public Map<String,Object> review(String id,String item) {
        try(var lease=locks.acquire(id)) {
            var c=s.connection(id,false);s.permission("warehouse:integrations:review",c);
            var inbox=s.inbox(id,item,false);var linked=linked(item,false);
            if(linked!=null)return linked;
            var authority=s.authority(id,false);
            var remote=s.inspect(c,WarehouseJson.map((String)inbox.get("document_json")),authority);
            return tx.execute(status->{var current=s.connection(id,true);s.permission("warehouse:integrations:review",current);return preview(current,s.inbox(id,item,true),s.authority(id,true),remote);});
        }
    }
    public Map<String,Object> accept(String id,String item,Map<String,Object> input) {
        keys(input,Set.of("requestId","reviewHash"));String request=text(input,"requestId",64),reviewHash=text(input,"reviewHash",64);
        if(!reviewHash.matches("[a-f0-9]{64}"))throw WarehouseException.invalid("Invalid review hash");
        String hash=digest(id,Map.of("item",item,"input",input));
        try(var lease=locks.acquire(id)) {
            var c=s.connection(id,false);s.permission("warehouse:integrations:accept",c);s.inbox(id,item,false);
            var replay=s.replay(request,"INTEGRATION_ACCEPT",hash,false);if(replay!=null){s.records.scoped("orders",(String)replay.get("orderId"),false);return replay;}
            var existing=linked(item,false);
            if(existing!=null)return tx.execute(status->{s.permission("warehouse:integrations:accept",s.connection(id,true));s.replay(request,"INTEGRATION_ACCEPT",hash,true);return s.complete(request,linked(item,true));});
            var remote=s.inspect(c,WarehouseJson.map((String)s.inbox(id,item,false).get("document_json")),s.authority(id,false));
            return tx.execute(status->{
                var current=s.connection(id,true);s.permission("warehouse:integrations:accept",current);
                var prior=s.replay(request,"INTEGRATION_ACCEPT",hash,true);if(prior!=null)return prior;
                var inbox=s.inbox(id,item,true);var authority=s.authority(id,true);var preview=preview(current,inbox,authority,remote);
                if(!Boolean.TRUE.equals(preview.get("ready")))throw new IntegrationFailure("ORDER_MAPPING_REQUIRES_REVIEW");
                if(!reviewHash.equals(preview.get("reviewHash")))throw new IntegrationFailure("ACCEPTANCE_REVIEW_CHANGED");
                var document=object(remote.get("order"));String orderId=UUID.randomUUID().toString();
                var body=orderInput(current,authority,document,preview.get("warehouseLines"),request);
                var order=orders.createIntegrated(orderId,body,Map.of("integrationConnection",id,"integrationInbox",item,"sourceOrderId",document.get("externalId"),"sourceReference",document.get("reference")));
                var evidence=new LinkedHashMap<>(preview);evidence.put("acceptedAt",Instant.now().toString());
                s.jdbc.update("INSERT INTO wh_integration_order(inbox_id,connection_id,source_key,external_id,order_id,evidence_json,actor_id) VALUES(?,?,?,?,?,?,?)",item,id,authority.get("source_key"),document.get("externalId"),orderId,WarehouseJson.write(evidence),s.scope.actor());
                var result=Map.<String,Object>of("accepted",true,"orderId",orderId,"reference",order.get("reference"),"inboxId",item);
                s.records.audit("INTEGRATION_ACCEPT","integrationInbox",item,null,result);return s.complete(request,result);
            });
        }
    }

    public Map<String,Object> resolve(String id,Map<String,Object> input) {
        keys(input,Set.of("kind","item","payload"));String kind=text(input,"kind",16);var payload=object(input.get("payload"));String request=text(payload,"requestId",64);
        if(!Set.of("authority","mapping","accept").contains(kind))throw WarehouseException.invalid("Invalid pending operation");
        if(!"accept".equals(kind))s.scope.requireAdministrator();
        String item="accept".equals(kind)?text(input,"item",64):"",type="INTEGRATION_"+kind.toUpperCase(Locale.ROOT),hash=digest(id,"accept".equals(kind)?Map.of("item",item,"input",payload):payload);
        try(var lease=locks.acquire(id)){return tx.execute(status->{
            var connection=s.connection(id,true);s.permission("warehouse:integrations:"+kind,connection);if(!item.isEmpty())s.inbox(id,item,true);
            Map<String,Object> previous;
            try{previous=s.replay(request,type,hash,true);}catch(IntegrationFailure e){if("ORIGINAL_REQUEST_CANCELLED".equals(e.code()))return Map.of("requestCancelled",true);throw e;}
            if(previous!=null){if("accept".equals(kind))s.records.scoped("orders",(String)previous.get("orderId"),true);return previous;}
            // The journal tombstone and connection lock also reject a late original POST.
            var cancelled=Map.<String,Object>of("requestCancelled",true);s.complete(request,cancelled);
            s.records.audit("INTEGRATION_REQUEST_CANCEL","integrationConnections",id,null,Map.of("requestId",request,"operation",kind));return cancelled;
        });}
    }

    private Map<String,Object> preview(Map<String,Object> c,Map<String,Object> inbox,Map<String,Object> authority,Map<String,Object> remote) {
        s.enabled(c);if(authority.isEmpty()||!flag(authority.get("active")))throw new IntegrationFailure("SOURCE_AUTHORITY_REQUIRED");
        if(!WooCommerceVerification.fingerprint(WarehouseJson.map((String)inbox.get("document_json"))).equals(remote.get("fingerprint")))throw new IntegrationFailure("SOURCE_ORDER_CHANGED");
        var document=object(remote.get("order"));var issues=new ArrayList<String>();var mapped=new ArrayList<Map<String,Object>>();var quantities=new TreeMap<String,Long>();
        var client=s.records.get("clients",(String)c.get("client_id"),true);var warehouse=s.records.get("warehouses",(String)c.get("warehouse_id"),true);var channel=s.records.get("channels",(String)c.get("channel_id"),true);
        if(!Objects.equals(client.get("currency"),document.get("currency")))issues.add("SOURCE_CURRENCY_MISMATCH");
        for(var line:WarehouseJson.maps(remote.get("lines"))) {
            var result=new LinkedHashMap<>(line);mapped.add(result);if(!Boolean.TRUE.equals(line.get("physical")))continue;
            var rows=s.jdbc.queryForList("SELECT * FROM wh_integration_mapping WHERE connection_id=? AND external_product_id=? AND external_variation_id=? FOR UPDATE",c.get("id"),line.get("productId"),line.get("variationId"));
            if(rows.isEmpty()||!flag(rows.get(0).get("active"))||!Objects.equals(line.get("sku"),rows.get(0).get("external_sku"))){result.put("issue","SKU_MAPPING_MISSING");issues.add("SKU_MAPPING_MISSING");continue;}
            var mapping=rows.get(0);String product=(String)mapping.get("product_id");
            try {var local=s.product(product,(String)c.get("client_id"),true);result.put("product",product);result.put("localSku",local.get("sku"));result.put("localName",local.getOrDefault("name",""));result.put("productVersion",local.get("_version"));result.put("mappingRevision",mapping.get("revision"));quantities.merge(product,((Number)line.get("quantity")).longValue(),Math::addExact);}
            catch(IntegrationFailure e){result.put("issue",e.code());issues.add(e.code());}
        }
        long total=quantities.values().stream().mapToLong(Long::longValue).sum();if(total>10000000||quantities.size()>200||quantities.isEmpty())issues.add("WAREHOUSE_QUANTITY_INVALID");
        var warehouseLines=quantities.entrySet().stream().map(e->Map.<String,Object>of("product",e.getKey(),"quantity",e.getValue())).toList();
        String warehouseIssue="";
        if(issues.isEmpty())try{orders.validateIntegrated(orderInput(c,authority,document,warehouseLines,"source-review"));}catch(WarehouseException e){issues.add("WAREHOUSE_ORDER_INVALID");warehouseIssue=e.getMessage();}
        var out=new LinkedHashMap<String,Object>();out.put("connection",c.get("id"));out.put("connectionRevision",c.get("revision"));out.put("inbox",inbox.get("id"));out.put("inboxRevision",inbox.get("revision"));
        out.put("authorityRevision",authority.get("revision"));out.put("sourceIdentity",authority.get("source_identity"));out.put("fulfillmentMode",authority.get("fulfillment_mode"));out.put("cutoverAt",authority.get("cutover_at"));
        out.put("clientVersion",client.get("_version"));out.put("warehouseVersion",warehouse.get("_version"));out.put("channelVersion",channel.get("_version"));out.put("remote",remote);out.put("lines",mapped);out.put("warehouseLines",warehouseLines);out.put("issues",issues.stream().distinct().toList());out.put("ready",issues.isEmpty());
        out.put("warehouseIssue",warehouseIssue);
        out.put("reviewHash",IntegrationService.hash(IntegrationService.canonical(out)));return out;
    }
    private Map<String,Object> orderInput(Map<String,Object> connection,Map<String,Object> authority,Map<String,Object> document,Object lines,String request) {
        var body=new LinkedHashMap<>(object(document.get("shipping")));body.put("client",connection.get("client_id"));body.put("warehouse",connection.get("warehouse_id"));body.put("channelId",connection.get("channel_id"));
        body.put("reference","WC-"+((String)authority.get("source_key")).substring(0,16)+"-"+document.get("externalId"));body.put("lines",lines);body.put("orderValue",document.get("total"));body.put("requestId",request);return body;
    }
    private Map<String,Object> linked(String item,boolean lock) {
        var rows=s.jdbc.queryForList("SELECT order_id FROM wh_integration_order WHERE inbox_id=?"+(lock?" FOR UPDATE":""),item);
        if(rows.isEmpty())return null;var order=s.records.scoped("orders",(String)rows.get(0).get("order_id"),lock);
        return Map.of("accepted",true,"orderId",order.get("id"),"reference",order.get("reference"),"inboxId",item);
    }
}
