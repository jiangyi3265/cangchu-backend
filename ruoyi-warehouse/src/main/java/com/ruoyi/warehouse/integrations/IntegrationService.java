package com.ruoyi.warehouse.integrations;

import com.ruoyi.warehouse.data.WarehouseRepository;
import com.ruoyi.warehouse.security.WarehouseScope;
import com.ruoyi.warehouse.support.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class IntegrationService {
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(IntegrationService.class);
    private final JdbcTemplate jdbc;private final WarehouseRepository records;private final WarehouseScope scope;private final IntegrationVault vault;private final IntegrationTransport transport;private final IntegrationJobLock locks;private final TransactionTemplate tx;
    public IntegrationService(WarehouseRepository records,WarehouseScope scope,IntegrationVault vault,IntegrationTransport transport,IntegrationJobLock locks,PlatformTransactionManager manager){this.records=records;this.scope=scope;this.jdbc=records.jdbc();this.vault=vault;this.transport=transport;this.locks=locks;tx=new TransactionTemplate(manager);}
    public Map<String,Object> capabilities(){return Map.of("provider","woocommerce","credentialKeyConfigured",vault.configured(),"localTestMode",transport.testMode(),"intakeOnly",false,"warehouseImport",true,"remoteWrites",false);}
    public List<Map<String,Object>> connections(){var args=new ArrayList<Object>();String predicate=scope.predicate("integrationConnections",args);return jdbc.queryForList("SELECT r.* FROM wh_integration_connection r WHERE "+predicate+" ORDER BY r.created_at DESC,r.id LIMIT 200",args.toArray()).stream().map(this::publicConnection).toList();}
    public Map<String,Object> get(String id){return publicConnection(visible(id,false));}
    public Map<String,Object> save(String id,Map<String,Object> input){
        scope.requireAdministrator();keys(input,Set.of("requestId","_version","name","siteUrl","client","warehouse","channel","enabled","credentials"));String request=text(input,"requestId",64),digest=hash(canonical(input));String target=id==null?hash("integration:"+scope.actor()+":"+request):id;
        try(var lease=locks.acquire(target)){return tx.execute(status->{
            var rows=jdbc.queryForList("SELECT * FROM wh_integration_connection WHERE id=? FOR UPDATE",target);Map<String,Object> before=rows.isEmpty()?null:rows.get(0);
            if(before!=null&&request.equals(before.get("last_request_id"))){if(!digest.equals(before.get("last_input_hash")))throw WarehouseException.conflict("Request content changed / 原请求内容已改变");return publicConnection(before);}
            if(id==null&&before!=null||id!=null&&before==null)throw WarehouseException.conflict("Connection identity changed / 连接记录已改变");
            if(before!=null&&number(before,"revision")!=WarehouseJson.integer(input.get("_version"),"_version",1,Long.MAX_VALUE))throw WarehouseException.conflict("Connection changed; reload it / 连接已更新，请刷新");
            String name=text(input,"name",191),site=transport.canonicalSite(text(input,"siteUrl",512)),client=text(input,"client",64),warehouse=text(input,"warehouse",64),channel=text(input,"channel",64);
            if(!(input.get("enabled") instanceof Boolean enabled))throw WarehouseException.invalid("Choose enabled or disabled / 请明确启用或停用");
            if(before!=null&&(!site.equals(before.get("site_url"))||!client.equals(before.get("client_id"))||!warehouse.equals(before.get("warehouse_id"))||!channel.equals(before.get("channel_id"))))throw WarehouseException.conflict("Connection source and routing are fixed; deactivate it before planning a migration / 连接来源及归属不能直接修改，请停用后核对迁移方案");
            var c=records.scoped("clients",client,true);var w=records.scoped("warehouses",warehouse,true);var ch=records.scoped("channels",channel,true);
            String encrypted=before==null?null:(String)before.get("credentials_cipher");if(input.containsKey("credentials"))encrypted=vault.seal(target,object(input.get("credentials")));
            if(enabled){if(inactive(c)||inactive(w)||inactive(ch))throw WarehouseException.conflict("Client, warehouse or channel is inactive / 客户、仓库或渠道已停用");if(encrypted==null||!vault.configured())throw new IntegrationFailure("CREDENTIAL_KEY_MISSING");if(!transport.allowed(site))throw new IntegrationFailure("STORE_HOST_NOT_ALLOWED");vault.open(target,encrypted);}
            if(before==null)jdbc.update("INSERT INTO wh_integration_connection(id,provider,name,site_url,source_key,client_id,warehouse_id,channel_id,credentials_cipher,cursor_json,enabled,last_request_id,last_input_hash,created_by,updated_by) VALUES(?,'woocommerce',?,?,?,?,?,?,?,'{}',?,?,?,?,?)",target,name,site,hash("woocommerce:"+site),client,warehouse,channel,encrypted,enabled,request,digest,scope.actor(),scope.actor());
            else{Object previousJob=before.get("active_job");if(previousJob!=null)jdbc.update("UPDATE wh_integration_job SET state='FAILED',error_code='CONNECTION_CHANGED',finished_at=CURRENT_TIMESTAMP(6) WHERE id=? AND state='RUNNING'",previousJob);jdbc.update("UPDATE wh_integration_connection SET name=?,enabled=?,credentials_cipher=?,revision=revision+1,last_request_id=?,last_input_hash=?,active_job=NULL,updated_by=?,updated_at=CURRENT_TIMESTAMP(6) WHERE id=?",name,enabled,encrypted,request,digest,scope.actor(),target);}
            var after=publicConnection(connection(target,false));records.audit(before==null?"INTEGRATION_CREATE":"INTEGRATION_UPDATE","integrationConnections",target,before==null?null:publicConnection(before),after);return after;
        });}
    }
    public Map<String,Object> pull(String id,Map<String,Object> input){
        keys(input,Set.of("requestId","_version","since","restartWindow"));String request=text(input,"requestId",64),digest=hash(canonical(Map.of("connection",id,"input",input)));long actor=scope.actor();visible(id,false);
        try(var lease=locks.acquire(id)){
            Map<String,Object> job=tx.execute(status->{
                var c=visible(id,true);requirePull(actor,c);var prior=jdbc.queryForList("SELECT * FROM wh_integration_job WHERE actor_id=? AND request_id=? FOR UPDATE",actor,request);
                if(!prior.isEmpty()){var p=prior.get(0);if(!digest.equals(p.get("input_hash")))throw WarehouseException.conflict("Request content changed / 原请求内容已改变");if(!"RUNNING".equals(p.get("state")))return p;if(number(c,"revision")!=number(p,"connection_revision"))throw WarehouseException.conflict("Connection changed; use a new request / 连接已更新，请重新拉取");return p;}
                if(number(c,"revision")!=WarehouseJson.integer(input.get("_version"),"_version",1,Long.MAX_VALUE))throw WarehouseException.conflict("Connection changed; reload it / 连接已更新，请刷新");
                if(c.get("active_job")!=null)throw WarehouseException.conflict("An interrupted pull must be recovered with its original request / 上次拉取未结束，请先恢复原请求");
                Map<String,Object> cursor=WarehouseJson.map((String)c.get("cursor_json")),window=new LinkedHashMap<>();Instant until=Instant.now().minusSeconds(2).truncatedTo(ChronoUnit.SECONDS);boolean restart=Boolean.TRUE.equals(input.get("restartWindow"));
                if(input.containsKey("restartWindow")&&!(input.get("restartWindow") instanceof Boolean))throw WarehouseException.invalid("Invalid restart choice");
                if(cursor.containsKey("page")&&!restart){if(input.containsKey("since"))throw WarehouseException.invalid("Continue the saved window without changing its start / 继续分页时不能更改起点");window.putAll(cursor);}
                else{Instant since;if(cursor.containsKey("page"))since=Instant.parse((String)cursor.get("since"));else if(input.containsKey("since"))since=parseSince(input.get("since"));else if(cursor.containsKey("completedUntil"))since=Instant.parse((String)cursor.get("completedUntil")).minusSeconds(120);else throw WarehouseException.invalid("Choose the initial UTC start time / 首次拉取请选择 UTC 起点");if(!since.isBefore(until)||since.isBefore(Instant.parse("2000-01-01T00:00:00Z")))throw WarehouseException.invalid("Invalid UTC window / UTC 时间范围无效");window.put("since",since.toString());window.put("until",until.toString());window.put("page",1);}
                String jobId=UUID.randomUUID().toString();jdbc.update("INSERT INTO wh_integration_job(id,connection_id,actor_id,request_id,input_hash,input_json,connection_revision,state,window_json,result_json) VALUES(?,?,?,?,?,?,?,'RUNNING',?,'{}')",jobId,id,actor,request,digest,WarehouseJson.write(input),number(c,"revision"),WarehouseJson.write(window));jdbc.update("UPDATE wh_integration_connection SET active_job=? WHERE id=?",jobId,id);return jobRow(jobId,false);
            });
            if(!"RUNNING".equals(job.get("state")))return jobView(job,false);
            try{
                var c=connection(id,false);var credentials=vault.open(id,(String)c.get("credentials_cipher"));var page=WooCommerceReader.fetch(transport.reader((String)c.get("site_url"),credentials,Instant.now().plusSeconds(45)),WarehouseJson.map((String)job.get("window_json")));
                tx.executeWithoutResult(status->{var current=connection(id,true);requirePull(actor,current);if(number(current,"revision")!=number(job,"connection_revision")||!job.get("id").equals(current.get("active_job")))throw new IntegrationFailure("CONNECTION_CHANGED");storePage(current,job,page);});
            }catch(Exception failure){LOG.warn("Integration pull {} failed with {}",job.get("id"),failure.getClass().getSimpleName());String code=failure instanceof IntegrationFailure f?f.code():failure instanceof WarehouseException w&&w.getStatus()==403?"ACCESS_REVOKED":"LOCAL_COMMIT_FAILED";tx.executeWithoutResult(status->{connection(id,true);jdbc.update("UPDATE wh_integration_job SET state='FAILED',error_code=?,finished_at=CURRENT_TIMESTAMP(6) WHERE id=? AND state='RUNNING'",code,job.get("id"));jdbc.update("UPDATE wh_integration_connection SET active_job=NULL WHERE id=? AND active_job=?",id,job.get("id"));records.audit("INTEGRATION_PULL_FAILED","integrationJobs",String.valueOf(job.get("id")),null,Map.of("connection",id,"errorCode",code));});}
            visible(id,false);return jobView(jobRow(String.valueOf(job.get("id")),false),false);
        }
    }
    private void storePage(Map<String,Object> connection,Map<String,Object> job,WooCommerceReader.Page page){
        int created=0,changed=0,unchanged=0,stale=0;String connectionId=(String)connection.get("id"),jobId=(String)job.get("id");
        for(var order:page.orders()){
            String external=(String)order.get("externalId"),document=WarehouseJson.write(order),digest=hash(canonical(order));var previous=jdbc.queryForList("SELECT * FROM wh_integration_inbox WHERE connection_id=? AND external_id=? FOR UPDATE",connectionId,external);String inbox;long revision;
            Timestamp time=Timestamp.from(Instant.parse((String)order.get("updatedAt")));
            if(previous.isEmpty()){inbox=UUID.randomUUID().toString();revision=1;jdbc.update("INSERT INTO wh_integration_inbox(id,connection_id,external_id,reference,state,source_updated_at,document_json,document_hash,last_job) VALUES(?,?,?,?,?,?,?,?,?)",inbox,connectionId,external,order.get("reference"),order.get("state"),time,document,digest,jobId);created++;}
            else{var old=previous.get(0);if(time.toInstant().isBefore(Instant.parse(utc(old.get("source_updated_at"))))){stale++;continue;}if(digest.equals(old.get("document_hash"))){unchanged++;continue;}inbox=(String)old.get("id");revision=number(old,"revision")+1;jdbc.update("UPDATE wh_integration_inbox SET reference=?,state=?,source_updated_at=?,document_json=?,document_hash=?,last_job=?,revision=?,updated_at=CURRENT_TIMESTAMP(6) WHERE id=?",order.get("reference"),order.get("state"),time,document,digest,jobId,revision,inbox);changed++;}
            jdbc.update("INSERT INTO wh_integration_event(inbox_id,revision,job_id,document_json) VALUES(?,?,?,?)",inbox,revision,jobId,document);
        }
        var result=Map.of("received",page.orders().size(),"created",created,"changed",changed,"unchanged",unchanged,"staleIgnored",stale,"remoteTotal",page.total(),"hasMore",page.next().containsKey("page"));
        jdbc.update("UPDATE wh_integration_connection SET cursor_json=?,active_job=NULL WHERE id=?",WarehouseJson.write(page.next()),connectionId);jdbc.update("UPDATE wh_integration_job SET state='SUCCEEDED',result_json=?,finished_at=CURRENT_TIMESTAMP(6) WHERE id=? AND state='RUNNING'",WarehouseJson.write(result),jobId);records.audit("INTEGRATION_PULL","integrationJobs",jobId,null,Map.of("connection",connectionId,"result",result));
    }
    public Map<String,Object> jobs(String id,int page,int size){visible(id,false);pagination(page,size);long count=jdbc.queryForObject("SELECT COUNT(*) FROM wh_integration_job WHERE connection_id=?",Long.class,id);var jobs=jdbc.queryForList("SELECT * FROM wh_integration_job WHERE connection_id=? ORDER BY created_at DESC,id LIMIT ? OFFSET ?",id,size,(page-1)*size).stream().map(j->jobView(j,true)).toList();return Map.of("rows",jobs,"total",count);}
    public Map<String,Object> inbox(String id,int page,int size,String state){visible(id,false);pagination(page,size);if(!state.isEmpty()&&!Set.of("AWAITING_REVIEW","HELD","CANCELLED","FULFILLED").contains(state))throw WarehouseException.invalid("Invalid inbox state");String clause="connection_id=?"+(state.isEmpty()?"":" AND state=?");var args=new ArrayList<Object>();args.add(id);if(!state.isEmpty())args.add(state);long count=jdbc.queryForObject("SELECT COUNT(*) FROM wh_integration_inbox WHERE "+clause,Long.class,args.toArray());args.add(size);args.add((page-1)*size);var rows=jdbc.queryForList("SELECT id,external_id,reference,revision,state,source_updated_at,updated_at,(SELECT accepted.order_id FROM wh_integration_order accepted WHERE accepted.inbox_id=wh_integration_inbox.id) AS warehouse_order_id FROM wh_integration_inbox WHERE "+clause+" ORDER BY updated_at DESC,id LIMIT ? OFFSET ?",args.toArray());rows.forEach(row->{for(String key:List.of("source_updated_at","updated_at"))row.put(key,utc(row.get(key)));});return Map.of("rows",rows,"total",count);}
    public Map<String,Object> inboxDetail(String id,String item){
        visible(id,false);var rows=jdbc.queryForList("SELECT * FROM wh_integration_inbox WHERE connection_id=? AND id=?",id,item);if(rows.isEmpty())throw WarehouseException.missing();var row=rows.get(0);
        var history=jdbc.queryForList("SELECT revision,job_id,created_at FROM wh_integration_event WHERE inbox_id=? ORDER BY revision DESC LIMIT 100",item);history.forEach(event->event.put("created_at",utc(event.get("created_at"))));
        var linked=jdbc.queryForList("SELECT order_id FROM wh_integration_order WHERE inbox_id=?",item);
        return Map.of("id",item,"revision",row.get("revision"),"document",WarehouseJson.map((String)row.get("document_json")),"history",history,"warehouseOrderId",linked.isEmpty()?"":linked.get(0).get("order_id"));
    }
    private Map<String,Object> publicConnection(Map<String,Object> row){var out=new LinkedHashMap<String,Object>();for(var entry:Map.of("id","id","name","name","provider","provider","site_url","siteUrl","client_id","client","warehouse_id","warehouse","channel_id","channel","revision","_version").entrySet())out.put(entry.getValue(),row.get(entry.getKey()));out.put("enabled",enabled(row));out.put("credentialConfigured",row.get("credentials_cipher")!=null);out.put("hostAllowed",transport.allowed((String)row.get("site_url")));out.put("cursor",WarehouseJson.map((String)row.get("cursor_json")));out.put("activeJob",row.get("active_job"));if(number(row,"updated_by")==scope.actor())out.put("lastRequestId",row.get("last_request_id"));return out;}
    private Map<String,Object> connection(String id,boolean lock){var rows=jdbc.queryForList("SELECT * FROM wh_integration_connection WHERE id=?"+(lock?" FOR UPDATE":""),id);if(rows.isEmpty())throw WarehouseException.missing();return rows.get(0);}
    private Map<String,Object> visible(String id,boolean lock){var c=connection(id,lock);scope.assertRecord("integrationConnections",Map.of("client",c.get("client_id"),"warehouse",c.get("warehouse_id")));return c;}
    private Map<String,Object> jobRow(String id,boolean lock){var rows=jdbc.queryForList("SELECT * FROM wh_integration_job WHERE id=?"+(lock?" FOR UPDATE":""),id);if(rows.isEmpty())throw WarehouseException.missing();return rows.get(0);}
    private Map<String,Object> jobView(Map<String,Object> row,boolean live){var result=new LinkedHashMap<String,Object>();for(String k:List.of("id","state","actor_id","error_code","created_at","finished_at"))result.put(k,Set.of("created_at","finished_at").contains(k)?utc(row.get(k)):row.get(k));result.put("window",WarehouseJson.map((String)row.get("window_json")));result.put("result",WarehouseJson.map((String)row.get("result_json")));boolean owner=number(row,"actor_id")==scope.actor();if(owner){result.put("requestId",row.get("request_id"));result.put("originalInput",WarehouseJson.map((String)row.get("input_json")));}result.put("connectionVersion",row.get("connection_revision"));result.put("recoverable",owner&&"RUNNING".equals(row.get("state"))&&(!live||jdbc.queryForObject("SELECT IS_USED_LOCK(?)",Long.class,IntegrationJobLock.name((String)row.get("connection_id")))==null));return result;}
    private void requirePull(long actor,Map<String,Object> c){
        if(jdbc.queryForObject("SELECT COUNT(*) FROM sys_user WHERE user_id=? AND status='0' AND del_flag='0'",Long.class,actor)!=1)throw WarehouseException.forbidden();
        if(actor!=1){long grants=jdbc.queryForObject("SELECT COUNT(*) FROM sys_user_role ur JOIN sys_role r ON r.role_id=ur.role_id JOIN sys_role_menu rm ON rm.role_id=r.role_id JOIN sys_menu m ON m.menu_id=rm.menu_id WHERE ur.user_id=? AND r.status='0' AND r.del_flag='0' AND m.status='0' AND m.perms='warehouse:integrations:pull'",Long.class,actor);if(grants==0||!scope.granted("CLIENT",(String)c.get("client_id"))||!scope.granted("WAREHOUSE",(String)c.get("warehouse_id")))throw WarehouseException.forbidden();}
        if(!enabled(c))throw new IntegrationFailure("CONNECTION_DISABLED");if(inactive(records.get("clients",(String)c.get("client_id"),true))||inactive(records.get("warehouses",(String)c.get("warehouse_id"),true))||inactive(records.get("channels",(String)c.get("channel_id"),true)))throw new IntegrationFailure("ROUTING_INACTIVE");
    }
    static String utc(Object value){if(value==null)return null;if(value instanceof java.time.LocalDateTime d)return d.toInstant(ZoneOffset.UTC).toString();if(value instanceof Timestamp t)return t.toInstant().toString();throw new IllegalStateException("Unexpected integration timestamp type");}
    private static boolean inactive(Map<String,Object> row){return Boolean.FALSE.equals(row.get("active"))||"N".equals(row.get("active"))||Boolean.TRUE.equals(row.get("onStop"));}
    private static boolean enabled(Map<String,Object> row){return Boolean.TRUE.equals(row.get("enabled"))||row.get("enabled") instanceof Number n&&n.intValue()==1;}
    private static long number(Map<String,Object> row,String name){return ((Number)row.get(name)).longValue();}
    private static void pagination(int page,int size){if(page<1||page>100000||size<1||size>100)throw WarehouseException.invalid("Invalid pagination");}
    private static Instant parseSince(Object value){try{return Instant.parse(String.valueOf(value)).truncatedTo(ChronoUnit.SECONDS);}catch(Exception e){throw WarehouseException.invalid("Use an ISO UTC start time / 起点需为 ISO UTC 时间");}}
    private static String text(Map<String,Object> map,String name,int max){Object value=map.get(name);if(!(value instanceof String s)||s.isBlank()||s.length()>max||s.chars().anyMatch(c->c<32||c==127))throw WarehouseException.invalid("Invalid "+name);return s.strip();}
    private static void keys(Map<String,Object> map,Set<String> allowed){if(map==null||!allowed.containsAll(map.keySet())||WarehouseJson.write(map).length()>8192)throw WarehouseException.invalid("Invalid integration input / 对接资料格式无效");}
    @SuppressWarnings("unchecked") private static Map<String,Object> object(Object value){if(!(value instanceof Map<?,?> map))throw WarehouseException.invalid("Invalid credentials / 凭据格式无效");return (Map<String,Object>)map;}
    static String hash(String s){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException();}}
    static String canonical(Object value){return WarehouseJson.write(canonicalValue(value));}
    private static Object canonicalValue(Object value){if(value instanceof Map<?,?> map){var sorted=new TreeMap<String,Object>();map.forEach((k,v)->sorted.put(String.valueOf(k),canonicalValue(v)));return sorted;}if(value instanceof List<?> list)return list.stream().map(IntegrationService::canonicalValue).toList();return value;}
}
