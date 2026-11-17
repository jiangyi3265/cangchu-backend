package com.ruoyi.warehouse.finance;

import com.ruoyi.warehouse.billing.*;
import com.ruoyi.warehouse.data.WarehouseRepository;
import com.ruoyi.warehouse.security.WarehouseScope;
import com.ruoyi.warehouse.support.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class StorageHistoryService {
    private final WarehouseRepository records;private final WarehouseScope scope;private final FinanceRuleService rules;private final JdbcTemplate jdbc;
    public StorageHistoryService(WarehouseRepository records,WarehouseScope scope,FinanceRuleService rules){this.records=records;this.scope=scope;this.rules=rules;jdbc=records.jdbc();}
    @Transactional(readOnly=true)
    public Map<String,Object> status(){rules.permission("query");return statusOf(jdbc.queryForMap("SELECT * FROM wh_storage_control WHERE id=1"));}
    private Map<String,Object> statusOf(Map<String,Object> control){
        var result=new LinkedHashMap<String,Object>();boolean enabled=Boolean.TRUE.equals(control.get("enabled"))||Integer.valueOf(1).equals(control.get("enabled"));result.put("enabled",enabled);
        Object stored=control.get("started_at");Instant at=stored==null?null:instant(stored);result.put("startedAt",at==null?null:at.toString());result.put("firstCompleteDay",at==null?null:firstDay(at).toString());result.put("baselineId",control.get("baseline_batch"));result.put("lastSequence",control.get("last_sequence"));return result;
    }
    @Transactional
    public Map<String,Object> start(Map<String,Object> input){
        rules.permission("edit");scope.requireAdministrator();FinanceInput.keys(input,"requestId","confirm");FinanceInput.id(input,"requestId");if(!FinanceInput.bool(input,"confirm",false))throw WarehouseException.invalid("Confirm history recording / 请确认开始记录仓储历史");
        if(Boolean.TRUE.equals(status().get("enabled")))return status();
        // This one-time baseline locks the full physical state before the commit
        // barrier. Normal captures lock only the rows their transaction changed.
        var keys=new TreeMap<String,Set<String>>();for(String kind:new TreeSet<>(StorageHistory.KINDS))keys.put(kind,new TreeSet<>(jdbc.queryForList("SELECT id FROM wh_record WHERE kind=? ORDER BY id FOR UPDATE",String.class,kind)));
        keys.put("inventory",new TreeSet<>(jdbc.queryForList("SELECT id FROM wh_inventory ORDER BY id FOR UPDATE",String.class)));
        keys.put("unidentified",new TreeSet<>(jdbc.queryForList("SELECT id FROM wh_asn_unidentified ORDER BY id FOR UPDATE",String.class)));
        var control=jdbc.queryForMap("SELECT * FROM wh_storage_control WHERE id=1 FOR UPDATE");if(Boolean.TRUE.equals(statusOf(control).get("enabled")))return statusOf(control);
        var batch=StorageHistory.append(jdbc,keys,true,((Number)control.get("last_sequence")).longValue()+1);
        jdbc.update("UPDATE wh_storage_control SET enabled=TRUE,baseline_batch=?,started_at=? WHERE id=1",batch.get("id"),Timestamp.from(Instant.parse(String.valueOf(batch.get("effectiveAt")))));
        records.audit("START_STORAGE_HISTORY","storageHistory",String.valueOf(batch.get("id")),null,Map.of("request",input,"baseline",batch));return status();
    }

    /** Historical reads use only immutable stream rows; current master prices are not consulted. */
    @Transactional(readOnly=true)
    public Map<String,Object> day(String client,String date){
        rules.permission("query");records.scoped("clients",client,false);LocalDate day=FinanceInput.date(Map.of("date",date),"date");validateRange(day,day);
        var timeline=timeline(client,day,day);var state=timeline.initial();var peaks=locationCounts(state,client);long sequence=timeline.openingSequence();var warehouses=warehouses(state,client);
        for(var batch:timeline.batches()){for(var change:batch.changes())state.apply(change);sequence=batch.sequence();mergePeak(peaks,locationCounts(state,client));warehouses.addAll(warehouses(state,client));}
        checkWarehouses(warehouses);var result=context(state,client,day,day,peaks);result.put("day",date);result.put("openingSequence",timeline.openingSequence());result.put("closingSequence",sequence);result.put("warehouseIds",warehouses);result.put("evidenceHash",FinanceInput.hash(result));return result;
    }
    List<Map<String,Object>> entries(String client,LocalDate from,LocalDate to){
        return periods(client,from,to,false);
    }
    Map<String,Object> reviewPeriod(String client,LocalDate from,LocalDate to){
        var periods=periods(client,from,to,true);
        if(periods.size()!=1)throw WarehouseException.conflict("Select one complete unbilled storage period / 请选择一个完整且未结算的仓储周期");
        var context=AdjustmentSupport.map(periods.get(0).get("context"));
        if(!from.toString().equals(context.get("from"))||!to.toString().equals(context.get("to")))throw WarehouseException.conflict("Select the exact unbilled period without previously billed dates / 请选择准确的未结算周期，不要包含已结算日期");
        return periods.get(0);
    }
    private List<Map<String,Object>> periods(String client,LocalDate from,LocalDate to,boolean review){
        validateRange(from,to);var timeline=timeline(client,from,to);var state=timeline.initial();var result=new ArrayList<Map<String,Object>>();int index=0;long sequence=timeline.openingSequence(),opening=sequence;
        LocalDate periodStart=from,periodEnd=null;String frequency="",type="",currency="";var peak=new TreeMap<String,Long>();var involved=new TreeSet<String>();
        var billed=new HashSet<>(jdbc.queryForList("SELECT billing_day FROM wh_storage_billed_day WHERE client_id=? AND billing_day>=? AND billing_day<=? FOR UPDATE",java.sql.Date.class,client,from.toString(),to.toString()).stream().map(java.sql.Date::toLocalDate).toList());
        for(LocalDate day=from;!day.isAfter(to);day=day.plusDays(1)){
            mergePeak(peak,locationCounts(state,client));involved.addAll(warehouses(state,client));Instant end=day.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC);
            while(index<timeline.batches().size()&&timeline.batches().get(index).at().isBefore(end)){var batch=timeline.batches().get(index++);for(var change:batch.changes())state.apply(change);sequence=batch.sequence();mergePeak(peak,locationCounts(state,client));involved.addAll(warehouses(state,client));}
            var owner=state.get("clients",client);String dailyFrequency=FinanceInput.option(owner,"storageFrequency","Daily","Daily","Weekly","Monthly"),dailyType=FinanceInput.option(owner,"storageType","Volumetric","Volumetric","VolumetricCarton","PerPalletUnit","StorageUnits","LocationTypeUsage","HighestPointLocationTypeUsage","StockManagementFee"),dailyCurrency=FinanceInput.option(owner,"currency","GBP","GBP","USD","EUR","CNY");
            if(periodEnd==null){frequency=dailyFrequency;type=dailyType;currency=dailyCurrency;var range=BillingPeriods.period(day.toString(),frequency);periodEnd=LocalDate.parse(range.get("to"));if(!range.get("from").equals(day.toString())||periodEnd.isAfter(to))throw WarehouseException.invalid("Select whole historical "+frequency+" periods / 请选择完整历史计费周期");}
            else if(!frequency.equals(dailyFrequency)||!type.equals(dailyType)||!currency.equals(dailyCurrency))throw WarehouseException.conflict("Storage frequency, mode or currency changed inside this period; review the transition / 周期内计费频率、方式或币种发生变化，需先核对过渡期");
            if(day.equals(periodEnd)){
                checkWarehouses(involved);var days=new ArrayList<String>();for(LocalDate d=periodStart;!d.isAfter(periodEnd);d=d.plusDays(1))days.add(d.toString());long used=days.stream().map(LocalDate::parse).filter(billed::contains).count();if(used!=0&&used!=days.size())throw WarehouseException.conflict("Part of this storage period was already charged / 本周期部分日期已计费，不能重叠收费");
                if(used==0){var context=context(state,client,periodStart,periodEnd,peak);
                    var period=new LinkedHashMap<String,Object>();period.put("context",context);period.put("warehouseIds",new ArrayList<>(involved));period.put("openingSequence",opening);period.put("closingSequence",sequence);period.put("historicalProducts",state.rows("products").stream().filter(p->client.equals(p.get("client"))).toList());
                    if(review){result.add(period);periodStart=day.plusDays(1);periodEnd=null;opening=sequence;peak=new TreeMap<>();involved=new TreeSet<>();continue;}
                    context=new StorageIdentityReviewService(records,scope,rules).applyApproved(period);validatePrices(context);Map<String,Object> quote;try{quote=BillingCalculator.storageQuote(context);}catch(IllegalArgumentException e){throw WarehouseException.conflict("Storage pricing requires correction / 仓储费资料需核对: "+e.getMessage());}
                    var entry=new LinkedHashMap<String,Object>();entry.put("source","storage:"+client+":"+periodStart+":"+periodEnd);entry.put("description","Storage / 仓储 "+periodStart+" - "+periodEnd);entry.put("type","Storage");entry.put("amount",quote.get("amount"));entry.put("breakdown",quote.get("lines"));entry.put("currency",currency);entry.put("warehouseIds",new ArrayList<>(involved));entry.put("storageDays",days);entry.put("storageContext",context);entry.put("storageEvidence",Map.of("openingSequence",opening,"closingSequence",sequence,"contextHash",FinanceInput.hash(context),"pricingPolicy","PERIOD_END_V1","peakPolicy","COMMITTED_STATES_V1"));result.add(entry);
                }
                periodStart=day.plusDays(1);periodEnd=null;opening=sequence;peak=new TreeMap<>();involved=new TreeSet<>();
            }
        }
        return result;
    }
    private Timeline timeline(String client,LocalDate from,LocalDate to){
        var control=jdbc.queryForMap("SELECT * FROM wh_storage_control WHERE id=1");var info=statusOf(control);if(!Boolean.TRUE.equals(info.get("enabled")))throw WarehouseException.conflict("Start storage history recording first / 请先启用仓储历史记录");
        if(from.isBefore(LocalDate.parse(String.valueOf(info.get("firstCompleteDay")))))throw WarehouseException.conflict("No complete history for this date; do not substitute current stock / 此日期没有完整历史，不能用当前库存代替");
        Timestamp begin=Timestamp.from(from.atStartOfDay().toInstant(ZoneOffset.UTC)),end=Timestamp.from(to.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC));
        Long before=jdbc.queryForObject("SELECT COALESCE(MAX(sequence_no),0) FROM wh_storage_batch WHERE effective_at<=?",Long.class,begin);long opening=before==null?0:before;
        var state=new State(client);var initial=jdbc.queryForList("SELECT c.* FROM wh_storage_change c JOIN (SELECT entity_kind,entity_id,MAX(sequence_no) seq FROM wh_storage_change WHERE sequence_no<=? GROUP BY entity_kind,entity_id) latest ON c.entity_kind=latest.entity_kind AND c.entity_id=latest.entity_id AND c.sequence_no=latest.seq WHERE (c.client_id=? OR c.client_id='') ORDER BY c.entity_kind,c.entity_id",opening,client);for(var change:initial)state.apply(change);
        if(!state.has("clients",client))throw WarehouseException.conflict("Client has no opening history for this period / 客户在周期开始时没有历史记录");
        var batches=new TreeMap<Long,Batch>();for(var row:jdbc.queryForList("SELECT c.*,b.effective_at FROM wh_storage_change c JOIN wh_storage_batch b ON b.id=c.batch_id AND b.sequence_no=c.sequence_no WHERE c.sequence_no>? AND b.effective_at<? AND (c.client_id=? OR c.client_id='' OR EXISTS (SELECT 1 FROM wh_storage_change prior WHERE prior.entity_kind=c.entity_kind AND prior.entity_id=c.entity_id AND prior.client_id=? AND prior.sequence_no<c.sequence_no)) ORDER BY c.sequence_no,c.entity_kind,c.entity_id",opening,end,client,client)){
            long seq=((Number)row.get("sequence_no")).longValue();batches.computeIfAbsent(seq,k->new Batch(seq,instant(row.get("effective_at")),new ArrayList<>())).changes().add(row);
        }
        return new Timeline(state,opening,new ArrayList<>(batches.values()));
    }
    private static Map<String,Object> context(State state,String client,LocalDate from,LocalDate to,Map<String,Long> peak){
        var stock=state.rows("inventory").stream().filter(r->client.equals(r.get("client"))&&((Number)r.get("onhand")).longValue()>0).toList();var unknown=state.rows("unidentified").stream().filter(r->client.equals(r.get("client"))&&((Number)r.get("onhand")).longValue()>0).toList();var products=state.rows("products").stream().filter(r->client.equals(r.get("client"))&&stock.stream().anyMatch(i->Objects.equals(i.get("product"),r.get("id")))).toList();var locations=state.rows("locations").stream().filter(l->stock.stream().anyMatch(i->Objects.equals(i.get("location"),l.get("id")))||unknown.stream().anyMatch(i->Objects.equals(i.get("location"),l.get("id")))).toList();
        var context=new LinkedHashMap<String,Object>();context.put("client",state.get("clients",client));context.put("stock",stock);context.put("unidentifiedStock",unknown);context.put("products",products);context.put("locations",locations);context.put("locationPrices",state.rows("locationPrices").stream().filter(r->WarehouseJson.empty(r.get("client"))||client.equals(r.get("client"))).toList());context.put("locationPeaks",new TreeMap<>(peak));context.put("from",from.toString());context.put("to",to.toString());context.put("frequency",state.get("clients",client).getOrDefault("storageFrequency","Daily"));return context;
    }
    private static Map<String,Long> locationCounts(State state,String client){
        var counts=new TreeMap<String,Long>();var used=new HashSet<String>();for(var row:physical(state))if(client.equals(row.get("client"))&&((Number)row.get("onhand")).longValue()>0){String id=WarehouseJson.text(row,"location");if(!used.add(id))continue;var location=state.get("locations",id);if(!"Unassigned".equals(location.get("name")))counts.merge(WarehouseJson.text(location,"type"),1L,Long::sum);}return counts;
    }
    private static List<Map<String,Object>> physical(State state){var rows=new ArrayList<>(state.rows("inventory"));rows.addAll(state.rows("unidentified"));return rows;}
    private static TreeSet<String> warehouses(State state,String client){var result=new TreeSet<String>();for(var row:physical(state))if(client.equals(row.get("client"))&&((Number)row.get("onhand")).longValue()>0)result.add(WarehouseJson.text(row,"warehouse"));return result;}
    private void checkWarehouses(Set<String> warehouses){for(String id:warehouses)if(!scope.granted("WAREHOUSE",id))throw WarehouseException.forbidden();}
    private static void mergePeak(Map<String,Long> target,Map<String,Long> counts){counts.forEach((k,v)->target.merge(k,v,Math::max));}
    private static Instant instant(Object value){if(value instanceof Timestamp t)return t.toInstant();if(value instanceof LocalDateTime t)return t.toInstant(ZoneOffset.UTC);throw new IllegalStateException("Unexpected storage timestamp type");}
    private static LocalDate firstDay(Instant at){var utc=at.atOffset(ZoneOffset.UTC);return utc.toLocalTime().equals(LocalTime.MIDNIGHT)?utc.toLocalDate():utc.toLocalDate().plusDays(1);}
    private static void validateRange(LocalDate from,LocalDate to){if(from.isAfter(to)||!to.isBefore(LocalDate.now(ZoneOffset.UTC))||java.time.temporal.ChronoUnit.DAYS.between(from,to)>365)throw WarehouseException.invalid("Select up to 366 completed UTC days");}
    static void validatePrices(Map<String,Object> context){
        var client=WarehouseJson.map(WarehouseJson.write(context.get("client")));String type=WarehouseJson.text(client,"storageType");if(type.isEmpty())type="Volumetric";
        if(!WarehouseJson.maps(context.get("unidentifiedStock")).isEmpty()&&!Set.of("LocationTypeUsage","HighestPointLocationTypeUsage","StockManagementFee").contains(type))throw WarehouseException.conflict("Historical unidentified goods need product and billing review; no zero fee is assumed / 此历史周期含未识别实物，需核对商品与计费资料，不能按零费用结算");
        var stock=WarehouseJson.maps(context.get("stock"));if("StockManagementFee".equals(type)){FinanceInput.number(client.get("stockManagementFee"),"stockManagementFee",false);return;}
        for(var p:WarehouseJson.maps(context.get("products")))if(stock.stream().anyMatch(i->Objects.equals(i.get("product"),p.get("id")))){
            if(Set.of("StorageUnits","PerPalletUnit").contains(type)){Object own=p.get("unitStoragePrice");if(WarehouseJson.empty(own)||FinanceInput.number(own,"unitStoragePrice",false).signum()==0)FinanceInput.number(client.get("unitStorageFee"),"unitStorageFee",false);}
            else if(Set.of("Volumetric","VolumetricCarton").contains(type))FinanceInput.number(client.get("storageRate"),"storageRate",false);
        }
    }
    private static final class State {
        final Map<String,Map<String,Map<String,Object>>> data=new TreeMap<>();
        final String client;State(String client){this.client=client;}
        void apply(Map<String,Object> change){String kind=String.valueOf(change.get("entity_kind")),id=String.valueOf(change.get("entity_id"));var value=StorageHistory.decoded(change);var group=data.computeIfAbsent(kind,k->new TreeMap<>());if(Boolean.TRUE.equals(value.get("deleted"))||!WarehouseJson.empty(change.get("client_id"))&&!client.equals(change.get("client_id")))group.remove(id);else group.put(id,value);}
        boolean has(String kind,String id){return data.getOrDefault(kind,Map.of()).containsKey(id);}
        Map<String,Object> get(String kind,String id){var value=data.getOrDefault(kind,Map.of()).get(id);if(value==null)throw WarehouseException.conflict("Missing historical "+kind+" evidence / 历史关联资料不完整");return value;}
        List<Map<String,Object>> rows(String kind){return new ArrayList<>(data.getOrDefault(kind,Map.of()).values());}
    }
    private record Batch(long sequence,Instant at,List<Map<String,Object>> changes) { }
    private record Timeline(State initial,long openingSequence,List<Batch> batches) { }
}
