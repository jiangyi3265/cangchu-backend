package com.ruoyi.warehouse.finance;

import com.ruoyi.warehouse.billing.BillingCalculator;
import com.ruoyi.warehouse.data.WarehouseRepository;
import com.ruoyi.warehouse.security.WarehouseScope;
import com.ruoyi.warehouse.support.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Evidenced financial interpretation of anonymous history; never changes physical history. */
@Service
public class StorageIdentityReviewService {
    private final WarehouseRepository records;private final WarehouseScope scope;private final FinanceRuleService rules;private final JdbcTemplate jdbc;
    private static final Set<String> PRODUCT_MODES=Set.of("StorageUnits","Volumetric","VolumetricCarton","PerPalletUnit");
    public StorageIdentityReviewService(WarehouseRepository records,WarehouseScope scope,FinanceRuleService rules){this.records=records;this.scope=scope;this.rules=rules;jdbc=records.jdbc();}
    @Transactional
    public Map<String,Object> context(String client,String from,String to){
        rules.permission("query");records.scoped("clients",client,true);FinanceInput.date(Map.of("from",from),"from");FinanceInput.date(Map.of("to",to),"to");var old=find(client,from,to);var review=old==null?null:getInternal(old,true);if(review!=null)assertAccess(review);var period=review!=null&&"CONSUMED".equals(review.get("status"))?map(map(review,"proposal"),"originalPeriod"):period(client,from,to);var result=new LinkedHashMap<>(period);result.put("review",review);result.put("evidenceHash",AdjustmentSupport.hash(period));return result;
    }
    @Transactional(readOnly=true)
    public Map<String,Object> products(String client,String keyword){
        rules.permission("query");records.scoped("clients",client,false);var listed=records.list("products",1,200,keyword,Map.of("client",client));
        return Map.of("rows",WarehouseJson.maps(listed.get("rows")).stream().map(p->Map.of("id",p.get("id"),"sku",p.get("sku"),"name",p.getOrDefault("name",""))).toList());
    }
    @Transactional(readOnly=true)
    public Map<String,Object> get(String id){rules.permission("query");var row=getInternal(id,false);assertAccess(row);var out=new LinkedHashMap<>(row);out.put("events",jdbc.query("SELECT action_name,actor_id,created_at,document_json,document_hash FROM wh_storage_identity_event WHERE review_id=? ORDER BY revision",(rs,n)->{var doc=checked(rs.getString(4),rs.getString(5));return Map.of("action",rs.getString(1),"actorId",rs.getLong(2),"at",rs.getTimestamp(3).toInstant().toString(),"document",doc);},id));return out;}
    @Transactional
    public Map<String,Object> preview(Map<String,Object> input){rules.permission("query");FinanceInput.keys(input,"client","from","to","planInput");String client=FinanceInput.id(input,"client");records.scoped("clients",client,true);return build(period(client,FinanceInput.date(input,"from").toString(),FinanceInput.date(input,"to").toString()),AdjustmentSupport.map(input.get("planInput")));}
    @Transactional
    public Map<String,Object> save(Map<String,Object> input){
        rules.permission("edit");FinanceInput.keys(input,"client","from","to","planInput","quoteHash","requestId","_version");String client=FinanceInput.id(input,"client"),from=FinanceInput.date(input,"from").toString(),to=FinanceInput.date(input,"to").toString(),request=FinanceInput.id(input,"requestId");records.scoped("clients",client,true);
        String hash=AdjustmentSupport.hash(input);var replay=claim(request,"STORAGE_IDENTITY_SAVE",hash);if(replay!=null){assertAccess(replay);return replay;}
        var proposed=build(period(client,from,to),AdjustmentSupport.map(input.get("planInput")));if(!Objects.equals(input.get("quoteHash"),proposed.get("quoteHash")))throw WarehouseException.conflict("Review preview changed; preview again / 复核预览已变化，请重新预览");
        String id=find(client,from,to);var old=id==null?null:getInternal(id,true);long version=old==null?0:((Number)old.get("_version")).longValue();
        if(old==null&&input.containsKey("_version")||old!=null&&WarehouseJson.integer(input.get("_version"),"_version",1,Long.MAX_VALUE)!=version)throw WarehouseException.conflict("Review changed; reload / 复核记录已变化，请刷新");
        if(old!=null&&!Set.of("DRAFT","REVOKED").contains(old.get("status")))throw WarehouseException.conflict("Revoke an unused approval before editing / 修改前请先撤销尚未使用的批准");
        var data=new LinkedHashMap<String,Object>();data.put("id",id==null?UUID.randomUUID().toString():id);data.put("client",client);data.put("from",from);data.put("to",to);data.put("status","DRAFT");data.put("preparedBy",scope.actor());data.put("preparedAt",Instant.now().toString());data.put("proposal",proposed);data.put("warehouseIds",proposed.get("warehouseIds"));return complete(request,hash,write(old,data,"SAVE"));
    }
    @Transactional
    public Map<String,Object> action(String id,Map<String,Object> input){
        FinanceInput.keys(input,"requestId","_version","action","reason");String action=FinanceInput.option(input,"action","","approve","revoke");rules.permission("approveStorageReview");String request=FinanceInput.id(input,"requestId"),reason=FinanceInput.text(input,"reason",500,true);var unlocked=getInternal(id,false);assertAccess(unlocked);records.scoped("clients",text(unlocked,"client"),true);
        String hash=AdjustmentSupport.hash(Map.of("id",id,"input",input));var replay=claim(request,"STORAGE_IDENTITY_"+action.toUpperCase(Locale.ROOT),hash);if(replay!=null){assertAccess(replay);return replay;}
        var old=getInternal(id,true);AdjustmentSupport.version(old,input);var changed=WarehouseJson.copy(old);
        if("approve".equals(action)){
            if(!"DRAFT".equals(old.get("status")))throw WarehouseException.conflict("Only a draft can be approved / 只能批准待审核草稿");
            if(((Number)old.get("preparedBy")).longValue()==scope.actor())throw new WarehouseException(403,"warehouse_separation_required","A different person must approve / 请由另一人审核批准");
            var proposal=map(old,"proposal");var fresh=build(period(text(old,"client"),text(old,"from"),text(old,"to")),map(proposal,"planInput"));if(!Objects.equals(proposal.get("quoteHash"),fresh.get("quoteHash")))throw WarehouseException.conflict("Evidence or product identity changed; save a new preview / 原始证据或商品身份已变化，请重新保存预览");
            changed.put("status","APPROVED");changed.put("approvedBy",scope.actor());changed.put("approvedAt",Instant.now().toString());
        }else{if(!"APPROVED".equals(old.get("status")))throw WarehouseException.conflict("Only an unused approval can be revoked / 只能撤销尚未结算的批准");unbilled(old);changed.put("status","REVOKED");}
        changed.put("decisionReason",reason);return complete(request,hash,write(old,changed,action.toUpperCase(Locale.ROOT)));
    }
    Map<String,Object> applyApproved(Map<String,Object> period){
        var context=map(period,"context");String type=text(map(context,"client"),"storageType");if(!PRODUCT_MODES.contains(type.isEmpty()?"Volumetric":type)||WarehouseJson.maps(context.get("unidentifiedStock")).isEmpty())return context;
        String client=text(map(context,"client"),"id"),id=find(client,text(context,"from"),text(context,"to"));if(id==null)throw WarehouseException.conflict("Unidentified historical goods require a reviewed identity allocation / 历史未识别实物需要先完成商品归属复核");
        var review=getInternal(id,true);assertAccess(review);if(!"APPROVED".equals(review.get("status")))throw WarehouseException.conflict("Historical identity review is not approved / 历史商品归属复核尚未批准");
        var proposal=map(review,"proposal");if(!Objects.equals(proposal.get("historyHash"),AdjustmentSupport.hash(period)))throw WarehouseException.conflict("Historical review evidence changed / 历史复核证据发生变化");
        verifyIdentities(proposal);var result=map(proposal,"resolvedContext");result.put("storageIdentityReview",Map.of("id",id,"version",review.get("_version"),"quoteHash",proposal.get("quoteHash"),"approvedBy",review.get("approvedBy"),"approvedAt",review.get("approvedAt")));return result;
    }
    void consume(Map<String,Object> context,String charge){
        if(!(context.get("storageIdentityReview") instanceof Map<?,?>))return;var proof=map(context,"storageIdentityReview");var old=getInternal(text(proof,"id"),true);assertAccess(old);
        if(!"APPROVED".equals(old.get("status"))||((Number)old.get("_version")).longValue()!=((Number)proof.get("version")).longValue()||!Objects.equals(map(old,"proposal").get("quoteHash"),proof.get("quoteHash")))throw WarehouseException.conflict("Storage review approval changed / 仓储复核批准已变化");
        if(((Number)old.get("approvedBy")).longValue()==scope.actor())throw new WarehouseException(403,"warehouse_separation_required","A different person must post the approved review / 请由另一人执行已批准复核的结算");
        var data=WarehouseJson.copy(old);data.put("status","CONSUMED");data.put("charge",charge);data.put("postedBy",scope.actor());data.put("postedAt",Instant.now().toString());write(old,data,"CONSUME");
    }
    private Map<String,Object> period(String client,String from,String to){return new StorageHistoryService(records,scope,rules).reviewPeriod(client,LocalDate.parse(from),LocalDate.parse(to));}
    private Map<String,Object> build(Map<String,Object> period,Map<String,Object> input){
        FinanceInput.keys(input,"allocations","productFacts","reason","evidenceReference");String reason=FinanceInput.text(input,"reason",500,true),reference=FinanceInput.text(input,"evidenceReference",200,true);var context=map(period,"context");var owner=map(context,"client");String client=text(owner,"id"),type=text(owner,"storageType");if(type.isEmpty())type="Volumetric";
        var unknown=WarehouseJson.maps(context.get("unidentifiedStock"));if(unknown.isEmpty()||!PRODUCT_MODES.contains(type))throw WarehouseException.invalid("This period does not need product identity review / 此周期不需要商品归属复核");
        if(!(input.get("allocations") instanceof List<?> lines)||lines.isEmpty()||lines.size()>2000)throw WarehouseException.invalid("Provide historical quantity allocations / 请分配历史实物数量");
        var allocations=new ArrayList<Map<String,Object>>();var totals=new TreeMap<String,Long>();var productIds=new TreeSet<String>();var duplicate=new HashSet<String>();
        for(var raw:lines){var line=AdjustmentSupport.map(raw);FinanceInput.keys(line,"arrival","product","quantity");String arrival=FinanceInput.id(line,"arrival"),product=FinanceInput.id(line,"product");long q=WarehouseJson.integer(line.get("quantity"),"quantity",1,10_000_000);if(unknown.stream().noneMatch(u->arrival.equals(u.get("id")))||!duplicate.add(arrival+":"+product))throw WarehouseException.invalid("Unknown or duplicate allocation / 实物记录无效或商品分配重复");totals.merge(arrival,q,Math::addExact);productIds.add(product);allocations.add(Map.of("arrival",arrival,"product",product,"quantity",q));}
        for(var row:unknown)if(!Objects.equals(totals.get(text(row,"id")),((Number)row.get("onhand")).longValue()))throw WarehouseException.conflict("Allocate exactly the historical physical quantity / 必须完整分配当时的实际数量，不能多分或漏分");
        allocations.sort(Comparator.comparing((Map<String,Object> r)->text(r,"arrival")).thenComparing(r->text(r,"product")));
        var supplied=new TreeMap<String,Map<String,Object>>();if(!(input.get("productFacts") instanceof List<?> facts)||facts.size()>2000)throw WarehouseException.invalid("Provide product facts as a list / 商品计费资料需要列表格式");
        for(var value:facts){var fact=AdjustmentSupport.map(value);String product=FinanceInput.id(fact,"product");if(!productIds.contains(product)||supplied.put(product,fact)!=null)throw WarehouseException.invalid("Unexpected or duplicate product facts / 商品资料无效或重复");}
        var historical=new TreeMap<String,Map<String,Object>>();for(var p:WarehouseJson.maps(period.get("historicalProducts")))historical.put(text(p,"id"),p);
        var products=new TreeMap<String,Map<String,Object>>();for(var p:WarehouseJson.maps(context.get("products")))products.put(text(p,"id"),p);
        var identities=new ArrayList<Map<String,Object>>();var normalizedFacts=new ArrayList<Map<String,Object>>();
        for(String product:productIds){var current=records.scoped("products",product,true);if(!client.equals(current.get("client")))throw WarehouseException.forbidden();identities.add(Map.of("id",product,"version",current.get("_version"),"sku",current.get("sku")));
            if(historical.containsKey(product)){if(supplied.containsKey(product))throw WarehouseException.invalid("Historical product facts cannot be overridden / 不能覆盖已有的历史商品计费资料");products.put(product,historical.get(product));}
            else{var fact=supplied.get(product);if(fact==null)throw WarehouseException.conflict("This product has no period-end history; provide evidenced billing facts / 此商品当时没有历史资料，请按凭据补充计费资料");var normalized=facts(fact,type);normalizedFacts.add(normalized);var p=new LinkedHashMap<>(normalized);p.remove("product");p.put("id",product);p.put("client",client);p.put("sku",current.get("sku"));products.put(product,p);}
        }
        var stock=new ArrayList<>(WarehouseJson.maps(context.get("stock")));int index=0;
        for(var line:allocations){var original=unknown.stream().filter(r->r.get("id").equals(line.get("arrival"))).findFirst().orElseThrow();var virtual=new LinkedHashMap<>(original);virtual.put("id","review:"+index++);virtual.put("product",line.get("product"));virtual.put("onhand",line.get("quantity"));virtual.put("reviewArrival",original.get("id"));virtual.put("layers",List.of(Map.of("quantity",line.get("quantity"),"receivedAt",original.get("arrivedAt"))));stock.add(virtual);}
        var resolved=WarehouseJson.copy(context);resolved.put("stock",stock);resolved.put("products",new ArrayList<>(products.values()));resolved.put("originalUnidentifiedStock",unknown);resolved.put("unidentifiedStock",List.of());StorageHistoryService.validatePrices(resolved);
        Map<String,Object> quote;try{quote=BillingCalculator.storageQuote(resolved);}catch(IllegalArgumentException e){throw WarehouseException.invalid("Review product billing facts / 请核对商品计费资料: "+e.getMessage());}
        var result=new LinkedHashMap<String,Object>();result.put("historyHash",AdjustmentSupport.hash(period));result.put("originalPeriod",period);result.put("warehouseIds",period.get("warehouseIds"));result.put("planInput",Map.of("allocations",allocations,"productFacts",normalizedFacts,"reason",reason,"evidenceReference",reference));result.put("productIdentities",identities);result.put("resolvedContext",resolved);result.put("quote",quote);result.put("quoteHash",AdjustmentSupport.hash(result));return result;
    }
    private Map<String,Object> facts(Map<String,Object> input,String type){
        var result=new LinkedHashMap<String,Object>();result.put("product",FinanceInput.id(input,"product"));String[] keys=switch(type){case "StorageUnits"->new String[]{"unitStoragePrice"};case "Volumetric"->new String[]{"volume"};case "VolumetricCarton"->new String[]{"unitsPerCarton","cartonHeight","cartonLength","cartonDepth"};default->new String[]{"unitsPerPallet","unitStoragePrice"};};
        var allowed=new ArrayList<>(List.of(keys));allowed.add("product");FinanceInput.keys(input,allowed.toArray(String[]::new));
        for(String key:keys){var n=FinanceInput.number(input.get(key),key,false);if(!"unitStoragePrice".equals(key)&&n.signum()==0)throw WarehouseException.invalid("Billing dimensions and capacities must be positive / 计费体积和包装容量必须大于零");result.put(key,n);}
        return result;
    }
    private void verifyIdentities(Map<String,Object> proposal){for(var identity:WarehouseJson.maps(proposal.get("productIdentities"))){var current=records.scoped("products",text(identity,"id"),true);if(((Number)current.get("_version")).longValue()!=((Number)identity.get("version")).longValue())throw WarehouseException.conflict("Product identity changed since review; revoke and recheck / 复核后商品资料已变化，请撤销并重新核对");}}
    private void assertAccess(Map<String,Object> row){records.scoped("clients",text(row,"client"),false);for(Object warehouse:(List<?>)row.get("warehouseIds"))if(!scope.granted("WAREHOUSE",String.valueOf(warehouse)))throw WarehouseException.forbidden();}
    private void unbilled(Map<String,Object> row){if(jdbc.queryForObject("SELECT COUNT(*) FROM wh_storage_billed_day WHERE client_id=? AND billing_day>=? AND billing_day<=?",Long.class,row.get("client"),row.get("from"),row.get("to"))>0)throw WarehouseException.conflict("This period is already billed; use financial adjustments / 此周期已结算，请使用财务调账流程");}
    private String find(String client,String from,String to){var ids=jdbc.queryForList("SELECT id FROM wh_storage_identity_review WHERE client_id=? AND from_date=? AND to_date=? FOR UPDATE",String.class,client,from,to);return ids.isEmpty()?null:ids.get(0);}
    private Map<String,Object> getInternal(String id,boolean lock){var list=jdbc.query("SELECT * FROM wh_storage_identity_review WHERE id=?"+(lock?" FOR UPDATE":""),(rs,n)->{var doc=checked(rs.getString("document_json"),rs.getString("document_hash"));if(!id.equals(doc.get("id"))||!rs.getString("client_id").equals(doc.get("client"))||!rs.getString("from_date").equals(doc.get("from"))||!rs.getString("to_date").equals(doc.get("to"))||rs.getLong("revision")!=((Number)doc.get("_version")).longValue()||!rs.getString("status").equals(doc.get("status")))throw WarehouseException.conflict("Review evidence mismatch / 复核证据不一致");return doc;},id);if(list.size()!=1)throw WarehouseException.missing();return list.get(0);}
    private static Map<String,Object> checked(String json,String hash){var value=WarehouseJson.map(json);if(!AdjustmentSupport.hash(value).equals(hash))throw WarehouseException.conflict("Review evidence checksum failed / 复核证据校验失败");return value;}
    private Map<String,Object> write(Map<String,Object> old,Map<String,Object> data,String action){
        long version=old==null?1:((Number)old.get("_version")).longValue()+1;data.put("_version",version);String id=text(data,"id"),json=WarehouseJson.write(data),hash=AdjustmentSupport.hash(data);if(json.length()>2000000)throw WarehouseException.invalid("Review evidence is too large / 复核资料超出单据容量");
        if(old==null)jdbc.update("INSERT INTO wh_storage_identity_review(id,client_id,from_date,to_date,revision,status,document_json,document_hash) VALUES(?,?,?,?,?,?,?,?)",id,data.get("client"),data.get("from"),data.get("to"),version,data.get("status"),json,hash);
        else if(jdbc.update("UPDATE wh_storage_identity_review SET revision=?,status=?,document_json=?,document_hash=? WHERE id=? AND revision=?",version,data.get("status"),json,hash,id,version-1)!=1)throw WarehouseException.conflict("Review changed / 复核记录已变化");
        jdbc.update("INSERT INTO wh_storage_identity_event(id,review_id,revision,action_name,actor_id,document_json,document_hash) VALUES(?,?,?,?,?,?,?)",UUID.randomUUID().toString(),id,version,action,scope.actor(),json,hash);records.audit("STORAGE_IDENTITY_"+action,"storageReviews",id,old,data);return data;
    }
    private Map<String,Object> claim(String request,String type,String hash){jdbc.update("INSERT INTO wh_operation_request(actor_id,request_id,operation_type,request_hash,response_json) VALUES(?,?,?,?,?) ON DUPLICATE KEY UPDATE request_id=request_id",scope.actor(),request,type,hash,"{}");return jdbc.queryForObject("SELECT operation_type,request_hash,response_json FROM wh_operation_request WHERE actor_id=? AND request_id=? FOR UPDATE",(rs,n)->{if(!type.equals(rs.getString(1))||!hash.equals(rs.getString(2)))throw WarehouseException.conflict("Request identity already used");var response=WarehouseJson.map(rs.getString(3));return response.isEmpty()?null:response;},scope.actor(),request);}
    private Map<String,Object> complete(String request,String hash,Map<String,Object> result){if(jdbc.update("UPDATE wh_operation_request SET response_json=? WHERE actor_id=? AND request_id=? AND request_hash=?",WarehouseJson.write(result),scope.actor(),request,hash)!=1)throw WarehouseException.conflict("Request journal changed");return result;}
    private static Map<String,Object> map(Map<String,Object> row,String key){return AdjustmentSupport.map(row.get(key));}
    private static String text(Map<String,Object> row,String key){return WarehouseJson.text(row,key);}
}
