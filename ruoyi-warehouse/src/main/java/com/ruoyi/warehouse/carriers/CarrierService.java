package com.ruoyi.warehouse.carriers;

import com.ruoyi.warehouse.data.WarehouseRepository;
import com.ruoyi.warehouse.integrations.IntegrationJobLock;
import com.ruoyi.warehouse.orders.OrderService;
import com.ruoyi.warehouse.support.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static com.ruoyi.warehouse.carriers.DpdContract.*;

@Service
public class CarrierService {
    private final CarrierAccess access;private final WarehouseRepository records;private final JdbcTemplate jdbc;private final CarrierVault vault;private final CarrierTransport transport;private final IntegrationJobLock locks;private final OrderService orders;private final TransactionTemplate tx;
    public CarrierService(CarrierAccess access,CarrierVault vault,CarrierTransport transport,IntegrationJobLock locks,OrderService orders,PlatformTransactionManager manager){this.access=access;records=access.records;jdbc=records.jdbc();this.vault=vault;this.transport=transport;this.locks=locks;this.orders=orders;tx=new TransactionTemplate(manager);}
    public Map<String,Object> capabilities(){return Map.of("provider","dpd-uk","credentialKeyConfigured",vault.configured(),"runtimeEnabled",transport.enabled(),"localTestMode",transport.testMode(),"labelFormat","ZPL","maxParcels",99,"shipmentCancellation",false,"warehouseHandover",false);}
    public List<Map<String,Object>> connections(){var args=new ArrayList<Object>();String scope=access.scope.predicate("carrierConnections",args);return jdbc.queryForList("SELECT r.* FROM wh_carrier_connection r WHERE "+scope+" ORDER BY created_at DESC,id LIMIT 200",args.toArray()).stream().map(this::publicConnection).toList();}
    public Map<String,Object> connection(String id){return publicConnection(connectionRow(id,false));}
    private Map<String,Object> connectionRow(String id,boolean lock){var rows=jdbc.queryForList("SELECT * FROM wh_carrier_connection WHERE id=?"+(lock?" FOR UPDATE":""),id);if(rows.isEmpty())throw WarehouseException.missing();var row=rows.get(0);access.route(row);return row;}
    public Map<String,Object> save(String id,Map<String,Object> input){
        access.scope.requireAdministrator();keys(input,Set.of("requestId","_version","name","client","warehouse","config","credentials","enabled"));String request=text(input.get("requestId"),"requestId",64,false),digest=hash(canonical(input)),target=id==null?hash("carrier:"+access.scope.actor()+":"+request):id;
        try(var lease=locks.acquire("carrier-connection:"+target)){return tx.execute(status->{
            requireOpen("config",request);
            var rows=jdbc.queryForList("SELECT * FROM wh_carrier_connection WHERE id=? FOR UPDATE",target);var before=rows.isEmpty()?null:rows.get(0);
            if(before!=null&&request.equals(before.get("last_request_id"))){if(!digest.equals(before.get("last_input_hash")))throw WarehouseException.conflict("Original carrier request changed / 原快递请求已改变");return publicConnection(before);}
            if(id==null&&before!=null||id!=null&&before==null)throw WarehouseException.conflict("Carrier connection changed");
            if(before!=null&&WarehouseJson.integer(input.get("_version"),"version",1,Long.MAX_VALUE)!=number(before,"revision"))throw WarehouseException.conflict("Carrier connection changed; reload / 快递连接已更新，请刷新");
            String client=text(input.get("client"),"client",64,false),warehouse=text(input.get("warehouse"),"warehouse",64,false),name=text(input.get("name"),"name",191,false);var config=settings(object(input.get("config")));
            if(!(input.get("enabled") instanceof Boolean active))throw WarehouseException.invalid("Select enabled or disabled");
            access.permission("warehouse:carriers:configure",client,warehouse,true);
            if(before!=null){var old=WarehouseJson.map((String)before.get("config_json"));if(!client.equals(before.get("client_id"))||!warehouse.equals(before.get("warehouse_id"))||!config.get("environment").equals(old.get("environment"))||!config.get("businessUnit").equals(old.get("businessUnit")))throw WarehouseException.conflict("Carrier routing, environment and business are fixed / 快递归属、环境与业务类型不能直接更改");}
            var c=records.scoped("clients",client,true);var w=records.scoped("warehouses",warehouse,true);String encrypted=before==null?null:(String)before.get("credentials_cipher"),account=before==null?null:(String)before.get("account_key");
            if(input.containsKey("credentials")){var credentials=DpdContract.credentials(object(input.get("credentials")));String next=hash(config.get("environment")+":"+config.get("businessUnit")+":"+credentials.get("appKey"));if(account!=null&&!account.equals(next))throw WarehouseException.conflict("Create a separate connection for another carrier account / 更换快递账号需建立独立连接");account=next;encrypted=vault.seal(target,credentials);}
            if(active){if(encrypted==null||!vault.configured())throw new CarrierFailure("CARRIER_KEY_MISSING");vault.open(target,encrypted);if(inactive(c)||inactive(w))throw new CarrierFailure("CARRIER_ROUTING_INACTIVE");}
            if(before==null)jdbc.update("INSERT INTO wh_carrier_connection(id,name,client_id,warehouse_id,config_json,credentials_cipher,account_key,enabled,last_request_id,last_input_hash,updated_by) VALUES(?,?,?,?,?,?,?,?,?,?,?)",target,name,client,warehouse,WarehouseJson.write(config),encrypted,account,active,request,digest,access.scope.actor());
            else jdbc.update("UPDATE wh_carrier_connection SET name=?,config_json=?,credentials_cipher=?,account_key=?,enabled=?,last_request_id=?,last_input_hash=?,revision=revision+1,updated_by=?,updated_at=CURRENT_TIMESTAMP(6) WHERE id=?",name,WarehouseJson.write(config),encrypted,account,active,request,digest,access.scope.actor(),target);
            var after=publicConnection(connectionRow(target,false));records.audit(before==null?"CARRIER_CONNECTION_CREATE":"CARRIER_CONNECTION_UPDATE","carrierConnections",target,before==null?null:publicConnection(before),after);return after;
        });}
    }
    private Map<String,Object> publicConnection(Map<String,Object> row){var out=new LinkedHashMap<String,Object>();for(var p:Map.of("id","id","name","name","client_id","client","warehouse_id","warehouse","revision","_version").entrySet())out.put(p.getValue(),row.get(p.getKey()));out.put("config",WarehouseJson.map((String)row.get("config_json")));out.put("enabled",flag(row.get("enabled")));out.put("credentialConfigured",row.get("credentials_cipher")!=null);if(number(row,"updated_by")==access.scope.actor())out.put("lastRequestId",row.get("last_request_id"));return out;}
    private void enabled(Map<String,Object> c){transport.requireEnabled();if(!flag(c.get("enabled")))throw new CarrierFailure("CARRIER_CONNECTION_DISABLED");if(inactive(records.get("clients",(String)c.get("client_id"),false))||inactive(records.get("warehouses",(String)c.get("warehouse_id"),false)))throw new CarrierFailure("CARRIER_ROUTING_INACTIVE");}
    private void matches(Map<String,Object> order,Map<String,Object> c){if(!Objects.equals(order.get("client"),c.get("client_id"))||!Objects.equals(order.get("warehouse"),c.get("warehouse_id")))throw WarehouseException.forbidden();}
    public Map<String,Object> services(String orderId,Map<String,Object> input){
        keys(input,Set.of("connection","notificationMobile"));String id=text(input.get("connection"),"connection",64,false),mobile=text(input.get("notificationMobile"),"notificationMobile",30,true);
        try(var lease=locks.acquire("carrier-order:"+orderId)){
            var c=connectionRow(id,false);enabled(c);var order=orders.carrierSnapshot(orderId,"","warehouse:carriers:services");matches(order,c);var recipient=recipient(order,mobile);var client=client(c,WarehouseJson.map((String)c.get("config_json")),Instant.now().plusSeconds(45));
            var services=client.publicServices(recipient,WarehouseJson.maps(order.get("parcels")));access.permission("warehouse:carriers:services",(String)c.get("client_id"),(String)c.get("warehouse_id"),false);
            return Map.of("services",services,"connectionVersion",c.get("revision"),"orderVersion",order.get("_version"));
        }
    }
    public Map<String,Object> book(String orderId,Map<String,Object> input){
        keys(input,Set.of("requestId","connection","networkCode","shipDate","notificationMobile","_version","connectionVersion"));String request=text(input.get("requestId"),"requestId",64,false),connection=text(input.get("connection"),"connection",64,false),network=text(input.get("networkCode"),"networkCode",255,false),date=text(input.get("shipDate"),"shipDate",10,false),mobile=text(input.get("notificationMobile"),"notificationMobile",30,true);
        String hash=hash(canonical(Map.of("order",orderId,"input",input))),id=hash("carrier-book:"+access.scope.actor()+":"+request);
        try(var lease=locks.acquire("carrier-order:"+orderId)){
            requireOpen("book",request);
            var existing=jdbc.queryForList("SELECT * FROM wh_carrier_shipment WHERE id=?",id);
            if(!existing.isEmpty()){var old=existing.get(0);access.route(old);access.permission("warehouse:carriers:book",(String)old.get("client_id"),(String)old.get("warehouse_id"),false);if(!hash.equals(old.get("request_hash")))throw WarehouseException.conflict("Original carrier request changed");return run(old,"warehouse:carriers:book");}
            var job=tx.execute(status->{
                var order=orders.carrierSnapshot(orderId,"","warehouse:carriers:book");var c=connectionRow(connection,true);matches(order,c);enabled(c);if(WarehouseJson.integer(input.get("_version"),"orderVersion",1,Long.MAX_VALUE)!=number(order,"_version")||WarehouseJson.integer(input.get("connectionVersion"),"connectionVersion",1,Long.MAX_VALUE)!=number(c,"revision"))throw WarehouseException.conflict("Order or carrier configuration changed; select the service again / 订单或快递配置已变，请重新选择服务");
                var snapshot=new LinkedHashMap<>(order);snapshot.put("recipient",recipient(order,mobile));snapshot.put("networkCode",network);snapshot.put("shipDate",date);totalWeight(WarehouseJson.maps(order.get("parcels")));shipmentDate(date,Instant.now());
                String reference="WH"+id.substring(0,23);jdbc.update("INSERT INTO wh_carrier_shipment(id,connection_id,order_id,client_id,warehouse_id,actor_id,request_id,request_hash,reference,state,connection_revision,snapshot_json,config_json) VALUES(?,?,?,?,?,?,?,?,?,'PREPARING',?,?,?)",id,connection,orderId,c.get("client_id"),c.get("warehouse_id"),access.scope.actor(),request,hash,reference,c.get("revision"),WarehouseJson.write(snapshot),c.get("config_json"));
                records.audit("CARRIER_PREPARE","carrierShipments",id,null,Map.of("order",orderId,"connection",connection,"reference",reference));return shipmentRow(id,false);
            });return run(job,"warehouse:carriers:book");
        }
    }
    public Map<String,Object> recover(String id){var initial=shipmentRow(id,false);access.permission("warehouse:carriers:recover",(String)initial.get("client_id"),(String)initial.get("warehouse_id"),false);try(var lease=locks.acquire("carrier-order:"+initial.get("order_id"))){return run(shipmentRow(id,false),"warehouse:carriers:recover");}}
    private void requireOpen(String kind,String request){if(jdbc.queryForObject("SELECT COUNT(*) FROM wh_carrier_closed_request WHERE actor_id=? AND request_id=? AND request_kind=?",Long.class,access.scope.actor(),request,kind)>0)throw new CarrierFailure("CARRIER_REQUEST_CLOSED");}
    public Map<String,Object> resolve(String kind,String resource,Map<String,Object> input){
        keys(input,Set.of("requestId"));String request=text(input.get("requestId"),"requestId",64,false);boolean booking="book".equals(kind);
        if(!booking&&!"config".equals(kind))throw WarehouseException.invalid("Unknown request kind");
        if(!booking)access.scope.requireAdministrator();
        try(var lease=locks.acquire((booking?"carrier-order:":"carrier-connection:")+resource)){return tx.execute(status->{
            if(booking){var order=records.scoped("orders",resource,true);access.permission("warehouse:carriers:book",WarehouseJson.text(order,"client"),WarehouseJson.text(order,"warehouse"),true);String id=hash("carrier-book:"+access.scope.actor()+":"+request);var jobs=jdbc.queryForList("SELECT * FROM wh_carrier_shipment WHERE id=?",id);if(!jobs.isEmpty()){if(!resource.equals(jobs.get(0).get("order_id")))throw WarehouseException.conflict("Original request belongs to another order");return Map.<String,Object>of("requestClosed",false,"shipment",publicShipment(jobs.get(0)));}}
            else{var configs=jdbc.queryForList("SELECT * FROM wh_carrier_connection WHERE id=?",resource);if(configs.isEmpty()&&!resource.equals(hash("carrier:"+access.scope.actor()+":"+request)))throw WarehouseException.missing();if(!configs.isEmpty()){var current=configs.get(0);access.permission("warehouse:carriers:configure",(String)current.get("client_id"),(String)current.get("warehouse_id"),true);if(request.equals(current.get("last_request_id"))&&access.scope.actor()==number(current,"updated_by"))return Map.<String,Object>of("requestClosed",false,"connection",publicConnection(current));}}
            var old=jdbc.queryForList("SELECT resource_id FROM wh_carrier_closed_request WHERE actor_id=? AND request_id=? AND request_kind=?",String.class,access.scope.actor(),request,kind);if(old.isEmpty()){jdbc.update("INSERT INTO wh_carrier_closed_request(actor_id,request_id,request_kind,resource_id) VALUES(?,?,?,?)",access.scope.actor(),request,kind,resource);records.audit("CARRIER_REQUEST_CLOSED",booking?"orders":"carrierConnections",resource,null,Map.of("requestId",request,"kind",kind));}else if(!resource.equals(old.get(0)))throw WarehouseException.conflict("Original request resource changed");
            return Map.<String,Object>of("requestClosed",true);
        });}
    }
    private Map<String,Object> run(Map<String,Object> job,String permission){
        String id=(String)job.get("id"),state=(String)job.get("state");
        if("SENDING".equals(state)){changeState(id,"UNKNOWN","CARRIER_CREATE_OUTCOME_UNKNOWN",null);return visibleResult(id,permission);}
        if(Set.of("LABELLED","REJECTED","UNKNOWN").contains(state))return visibleResult(id,permission);
        DpdClient client;var snapshot=WarehouseJson.map((String)job.get("snapshot_json"));var config=WarehouseJson.map((String)job.get("config_json"));Instant deadline=Instant.now().plusSeconds(45);
        try{
            var c=connectionRow((String)job.get("connection_id"),false);enabled(c);access.permission(permission,(String)job.get("client_id"),(String)job.get("warehouse_id"),false);client=client(c,config,deadline);
            if("PREPARING".equals(state)){
                var wire=client.prepare((String)job.get("reference"),(String)snapshot.get("networkCode"),(String)snapshot.get("shipDate"),object(snapshot.get("recipient")),WarehouseJson.maps(snapshot.get("parcels")));
                tx.executeWithoutResult(status->{var currentOrder=orders.carrierSnapshot((String)job.get("order_id"),id,permission);var current=connectionRow((String)job.get("connection_id"),true);enabled(current);if(number(current,"revision")!=number(job,"connection_revision")||number(currentOrder,"_version")!=number(snapshot,"_version")||!canonical(currentOrder.get("parcels")).equals(canonical(snapshot.get("parcels"))))throw WarehouseException.conflict("Carrier snapshot changed");if(Instant.now().isAfter(deadline.minusSeconds(2)))throw new CarrierFailure("CARRIER_TIME_BUDGET");setState(id,"SENDING","",null);records.audit("CARRIER_SEND_INTENT","carrierShipments",id,null,Map.of("order",job.get("order_id"),"reference",job.get("reference")));});
                state="SENDING";
                var identity=client.create(wire,WarehouseJson.maps(snapshot.get("parcels")).size());
                changeState(id,"CREATED","",identity);state="CREATED";
            }
            retrieve(shipmentRow(id,false),client,permission,deadline);
        }catch(Exception failure){
            String code=failure instanceof CarrierFailure f?f.code():failure instanceof WarehouseException w&&w.getStatus()==403?"CARRIER_ACCESS_REVOKED":"CARRIER_REQUEST_FAILED";
            // No external create occurs in PREPARING. After send intent, only documented rejection proves no shipment.
            String next="PREPARING".equals(state)?"REJECTED":"SENDING".equals(state)?failure instanceof CarrierFailure f&&f.definitelyRejected()?"REJECTED":"UNKNOWN":"CREATED";
            changeState(id,next,code,null);
        }
        return visibleResult(id,permission);
    }
    private DpdClient client(Map<String,Object> connection,Map<String,Object> config,Instant deadline){return new DpdClient(transport.client((String)config.get("environment"),deadline),config,vault.open((String)connection.get("id"),(String)connection.get("credentials_cipher")));}
    private void retrieve(Map<String,Object> job,DpdClient client,String permission,Instant deadline){
        String id=(String)job.get("id");var identity=WarehouseJson.map((String)job.get("identity_json"));var parcels=WarehouseJson.maps(WarehouseJson.map((String)job.get("snapshot_json")).get("parcels"));var numbers=(List<String>)identity.get("trackingNumbers");int fetched=0;
        for(int i=0;i<numbers.size();i++){
            if(jdbc.queryForObject("SELECT COUNT(*) FROM wh_carrier_label WHERE shipment_id=? AND parcel_index=?",Long.class,id,i)>0)continue;
            if(fetched>=10||Instant.now().isAfter(deadline.minusSeconds(2)))return;
            var c=connectionRow((String)job.get("connection_id"),false);enabled(c);access.permission(permission,(String)job.get("client_id"),(String)job.get("warehouse_id"),false);
            String label=client.label((String)identity.get("shipmentId"),numbers.get(i));int index=i;long total=jdbc.queryForObject("SELECT COALESCE(SUM(OCTET_LENGTH(label_text)),0) FROM wh_carrier_label WHERE shipment_id=?",Long.class,id);if(total+label.getBytes(StandardCharsets.UTF_8).length>DpdContract.MAX_LABEL_BYTES)throw new CarrierFailure("DPD_LABEL_LIMIT");
            tx.executeWithoutResult(status->{shipmentRow(id,true);jdbc.update("INSERT INTO wh_carrier_label(shipment_id,parcel_index,package_id,tracking_number,label_text,sha256) VALUES(?,?,?,?,?,?)",id,index,parcels.get(index).get("id"),numbers.get(index),label,hash(label));records.audit("CARRIER_LABEL_RECEIVED","carrierShipments",id,null,Map.of("parcelIndex",index,"package",parcels.get(index).get("id"),"tracking",numbers.get(index),"sha256",hash(label)));});fetched++;
        }
        if(jdbc.queryForObject("SELECT COUNT(*) FROM wh_carrier_label WHERE shipment_id=?",Long.class,id)==numbers.size())changeState(id,"LABELLED","",null);
    }
    private Map<String,Object> shipmentRow(String id,boolean lock){var rows=jdbc.queryForList("SELECT * FROM wh_carrier_shipment WHERE id=?"+(lock?" FOR UPDATE":""),id);if(rows.isEmpty())throw WarehouseException.missing();return rows.get(0);}
    private void changeState(String id,String state,String code,Map<String,Object> identity){tx.executeWithoutResult(status->{var before=shipmentRow(id,true);setState(id,state,code,identity);records.audit("CARRIER_STATE","carrierShipments",id,Map.of("state",before.get("state")),Map.of("state",state,"error",code));});}
    private void setState(String id,String state,String code,Map<String,Object> identity){if(identity==null)jdbc.update("UPDATE wh_carrier_shipment SET state=?,error_code=?,revision=revision+1,updated_at=CURRENT_TIMESTAMP(6) WHERE id=?",state,code,id);else jdbc.update("UPDATE wh_carrier_shipment SET state=?,error_code=?,identity_json=?,revision=revision+1,updated_at=CURRENT_TIMESTAMP(6) WHERE id=?",state,code,WarehouseJson.write(identity),id);}
    private Map<String,Object> visibleResult(String id,String permission){var row=shipmentRow(id,false);try{access.permission(permission,(String)row.get("client_id"),(String)row.get("warehouse_id"),false);}catch(WarehouseException e){throw new CarrierFailure("CARRIER_ACCESS_REVOKED");}return publicShipment(row);}
    public Map<String,Object> shipment(String id){var row=shipmentRow(id,false);access.route(row);return publicShipment(row);}
    public List<Map<String,Object>> orderShipments(String order){records.scoped("orders",order,false);return jdbc.queryForList("SELECT * FROM wh_carrier_shipment WHERE order_id=? ORDER BY created_at DESC,id LIMIT 100",order).stream().map(this::publicShipment).toList();}
    public Map<String,Object> shipments(int page,int size){if(page<1||page>100000||size<1||size>100)throw WarehouseException.invalid("Invalid pagination");var args=new ArrayList<Object>();String scope=access.scope.predicate("carrierShipments",args);long total=jdbc.queryForObject("SELECT COUNT(*) FROM wh_carrier_shipment r WHERE "+scope,Long.class,args.toArray());args.add(size);args.add((page-1)*size);return Map.of("total",total,"rows",jdbc.queryForList("SELECT r.* FROM wh_carrier_shipment r WHERE "+scope+" ORDER BY created_at DESC,id LIMIT ? OFFSET ?",args.toArray()).stream().map(this::publicShipment).toList());}
    private Map<String,Object> publicShipment(Map<String,Object> row){var out=new LinkedHashMap<String,Object>();for(var p:Map.of("id","id","order_id","orderId","connection_id","connection","reference","reference","state","state","error_code","errorCode","revision","_version").entrySet())out.put(p.getValue(),row.get(p.getKey()));var snapshot=WarehouseJson.map((String)row.get("snapshot_json"));out.put("orderReference",snapshot.get("reference"));out.put("config",WarehouseJson.map((String)row.get("config_json")));out.put("identity",WarehouseJson.map((String)row.get("identity_json")));out.put("parcels",snapshot.get("parcels"));out.put("networkCode",snapshot.get("networkCode"));out.put("shipDate",snapshot.get("shipDate"));out.put("labels",jdbc.queryForList("SELECT parcel_index AS parcelIndex,package_id AS packageId,tracking_number AS trackingNumber,sha256 FROM wh_carrier_label WHERE shipment_id=? ORDER BY parcel_index",row.get("id")));out.put("createdAt",utc(row.get("created_at")));out.put("updatedAt",utc(row.get("updated_at")));if(number(row,"actor_id")==access.scope.actor())out.put("requestId",row.get("request_id"));return out;}
    public byte[] label(String id,int index){var row=shipmentRow(id,false);access.route(row);access.permission("warehouse:carriers:label",(String)row.get("client_id"),(String)row.get("warehouse_id"),false);var labels=jdbc.queryForList("SELECT label_text,sha256 FROM wh_carrier_label WHERE shipment_id=? AND parcel_index=?",id,index);if(labels.isEmpty())throw WarehouseException.missing();String zpl=(String)labels.get(0).get("label_text");if(!hash(zpl).equals(labels.get(0).get("sha256")))throw new CarrierFailure("CARRIER_LABEL_CORRUPT");validateLabel(zpl);return zpl.getBytes(StandardCharsets.UTF_8);}
    static boolean flag(Object v){return Boolean.TRUE.equals(v)||v instanceof Number n&&n.intValue()==1;}
    static boolean inactive(Map<String,Object> row){return Boolean.FALSE.equals(row.get("active"))||"N".equals(row.get("active"))||WarehouseJson.enabled(row.get("onStop"));}
    static long number(Map<String,Object> row,String key){return ((Number)row.get(key)).longValue();}
    static String utc(Object value){if(value instanceof java.sql.Timestamp t)return t.toInstant().toString();if(value instanceof LocalDateTime t)return t.toInstant(ZoneOffset.UTC).toString();throw new IllegalStateException("Unknown carrier timestamp");}
    public static String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException();}}
    static String canonical(Object value){return WarehouseJson.write(sorted(value));}
    private static Object sorted(Object value){if(value instanceof Map<?,?> map){var result=new TreeMap<String,Object>();map.forEach((k,v)->result.put(String.valueOf(k),sorted(v)));return result;}if(value instanceof List<?> list)return list.stream().map(CarrierService::sorted).toList();return value;}
}
