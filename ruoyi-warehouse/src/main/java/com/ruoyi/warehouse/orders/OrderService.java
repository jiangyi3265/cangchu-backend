package com.ruoyi.warehouse.orders;

import com.ruoyi.warehouse.billing.BillingCalculator;
import com.ruoyi.warehouse.data.WarehouseRepository;
import com.ruoyi.warehouse.data.SharedMasterReferences;
import com.ruoyi.warehouse.finance.FinancePeriodService;
import com.ruoyi.warehouse.security.WarehouseScope;
import com.ruoyi.warehouse.support.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class OrderService {
    private static final Set<String> STATUSES = Set.of("NEW", "HOLDING", "ALLOCATED", "PICKED", "DESPATCHED", "CANCELLED");
    private final WarehouseRepository records;
    private final WarehouseScope scope;
    private final OrderStock stock;
    private final FinancePeriodService periods;
    private final OrderWork work;
    private final JdbcTemplate jdbc;
    private com.ruoyi.warehouse.integrations.IntegrationOrderGuard sourceGuard;
    private com.ruoyi.warehouse.carriers.CarrierOrderGuard carrierGuard;
    public OrderService(WarehouseRepository records, WarehouseScope scope, OrderStock stock, FinancePeriodService periods) { this.records = records; this.scope = scope; this.stock = stock; this.periods = periods; jdbc = records.jdbc(); work = new OrderWork(records, scope); }
    @org.springframework.beans.factory.annotation.Autowired
    public void setSourceGuard(com.ruoyi.warehouse.integrations.IntegrationOrderGuard sourceGuard){this.sourceGuard=sourceGuard;}
    @org.springframework.beans.factory.annotation.Autowired
    public void setCarrierGuard(com.ruoyi.warehouse.carriers.CarrierOrderGuard carrierGuard){this.carrierGuard=carrierGuard;}

    @Transactional(readOnly = true)
    public Map<String, Object> get(String id) { var order = records.scoped("orders", id, false); return view(order); }

    public Map<String, Object> list(int page, int size, String client, String warehouse, String status, String keyword) {
        if (page < 1 || page > 100000 || size < 1 || size > 200) throw WarehouseException.invalid("Invalid order pagination");
        List<Object> args = new ArrayList<>(); String where = "r.kind='orders' AND " + scope.predicate("orders", args);
        for (String[] filter : List.of(new String[]{"r.client_id", client}, new String[]{"r.warehouse_id", warehouse})) if (filter[1] != null && !filter[1].isBlank()) { if (filter[1].length() > 64) throw WarehouseException.invalid("Order filter is too long"); where += " AND " + filter[0] + "=?"; args.add(filter[1]); }
        if (status != null && !status.isBlank()) { if (!STATUSES.contains(status)) throw WarehouseException.invalid("Unsupported order status"); where += " AND JSON_UNQUOTE(JSON_EXTRACT(r.business_json,'$.status'))=?"; args.add(status); }
        String search = keyword == null ? "" : keyword.strip(); if (search.length() > 200) throw WarehouseException.invalid("Search text too long");
        if (!search.isEmpty()) { where += " AND LOCATE(?,r.display_name)>0"; args.add(search); }
        long total = Objects.requireNonNull(jdbc.queryForObject("SELECT COUNT(*) FROM wh_record r WHERE " + where, Long.class, args.toArray()));
        args.add(size); args.add((long) (page - 1) * size);
        List<Map<String, Object>> rows = jdbc.query("SELECT r.id,r.revision,r.business_json,r.created_at,r.updated_at FROM wh_record r WHERE " + where + " ORDER BY r.created_at DESC,r.id LIMIT ? OFFSET ?", (rs, n) -> {
            var row = WarehouseJson.map(rs.getString("business_json")); row.put("id", rs.getString("id")); row.put("_version", rs.getLong("revision")); row.put("createdAt", rs.getTimestamp("created_at").toInstant().toString()); row.put("updatedAt", rs.getTimestamp("updated_at").toInstant().toString()); return row;
        }, args.toArray());
        return Map.of("code", 200, "msg", "success", "total", total, "rows", rows);
    }

    @Transactional
    public Map<String, Object> save(String id, Map<String, Object> input) {
        Map<String, Object> data = OrderRequest.data(input, id != null); checkScope(data);
        if (id != null) records.scoped("orders", id, false);
        String requestId = OrderRequest.id(input, "requestId"), type = id == null ? "ORDER_CREATE" : "ORDER_UPDATE";
        Map<String, Object> payload = new LinkedHashMap<>(data); payload.put("id", id); if (id != null) payload.put("_version", input.get("_version"));
        String hash = OrderRequest.hash(payload); Map<String, Object> replay = claim(requestId, type, hash);
        if (replay != null) { records.scoped("orders", text(replay, "id"), true); return replay; }
        Map<String, Object> old = id == null ? null : records.scoped("orders", id, true);
        if(old!=null&&carrierGuard!=null)carrierGuard.available(id,"");
        Long version = old == null ? null : WarehouseJson.integer(input.get("_version"), "_version", 1, Long.MAX_VALUE);
        if (old != null && (!Objects.equals(old.get("_version"), version) || !"NEW".equals(old.get("status")) || stock.hasHistory(id))) throw WarehouseException.conflict("Only an unchanged NEW order without allocation history can be edited / 仅未分配的新订单可编辑");
        if (old != null) { nativeOrder(old);if(integrated(old))unsupported("Store orders must be corrected at their source / 店铺订单需在来源处理变更"); }
        SharedMasterReferences.bind(records,"channels","channelId","channel",data,old,input.containsKey("channelId"));
        data.putIfAbsent("channel","Manual");
        Context context = context(data, true);
        String orderId = id == null ? UUID.randomUUID().toString() : id;
        data.put("status", "NEW"); data.put("fulfillmentLines", WarehouseJson.maps(data.get("lines")).stream().map(LinkedHashMap::new).toList()); data.put("shippingMode", "MANUAL");
        data.put("scanRequired", enabled(context.client(), "requireScan") || old != null && enabled(old, "scanRequired"));
        Map<String, Object> saved = records.write("orders", orderId, data, version, natural(data), text(data, "client"));
        Map<String, String> references = new LinkedHashMap<>(); references.put("client", text(data, "client")); references.put("warehouse", text(data, "warehouse"));
        SharedMasterReferences.reference(references,data,"channelId");
        int index = 0; for (String product : context.products().keySet()) references.put("line" + index++, product); records.replaceReferences(orderId, references);
        records.audit(id == null ? "CREATE_MANUAL_ORDER" : "UPDATE_MANUAL_ORDER", "orders", orderId, old, saved);
        return complete(requestId, hash, view(saved));
    }

    /** Called only by the source acceptance transaction, after its live provider verification. */
    public void validateIntegrated(Map<String,Object> input){context(OrderRequest.data(input,false),true);}
    @Transactional
    public Map<String,Object> createIntegrated(String id,Map<String,Object> input,Map<String,Object> source) {
        var data=OrderRequest.data(input,false);checkScope(data);
        var context=context(data,true);
        var channel=records.get("channels",OrderRequest.id(input,"channelId"),true);
        if(inactive(channel))throw WarehouseException.conflict("Channel is inactive");
        data.put("channel",channel.get("name"));data.put("source","WOOCOMMERCE");data.put("workflow","JAVA_WOO_V1");data.put("shippingMode","INTEGRATION");
        data.put("status","NEW");data.put("scanRequired",true);data.put("fulfillmentLines",WarehouseJson.maps(data.get("lines")).stream().map(LinkedHashMap::new).toList());
        for(String key:List.of("integrationConnection","integrationInbox","sourceOrderId","sourceReference"))data.put(key,source.get(key));
        var saved=records.write("orders",id,data,null,natural(data),text(data,"client"));
        var references=new LinkedHashMap<String,String>();references.put("client",text(data,"client"));references.put("warehouse",text(data,"warehouse"));references.put("channelId",text(data,"channelId"));
        int index=0;for(String product:context.products().keySet())references.put("line"+index++,product);records.replaceReferences(id,references);
        records.audit("CREATE_INTEGRATED_ORDER","orders",id,null,saved);return saved;
    }

    @Transactional
    public Map<String, Object> action(String id, Map<String, Object> input) {
        Map<String, Object> payload = OrderRequest.action(input, id); records.scoped("orders", id, false);
        String requestId = OrderRequest.id(input, "requestId"), hash = OrderRequest.hash(payload);
        Map<String, Object> replay = claim(requestId, "ORDER_ACTION", hash); if (replay != null) return replay;
        Map<String, Object> before = records.scoped("orders", id, true); nativeOrder(before);
        if(carrierGuard!=null)carrierGuard.available(id,"");
        Map<String, Object> order = business(before); String action = text(payload, "action"), state = text(order, "status");
        if(integrated(order)&&"despatch".equals(action))unsupported("Carrier despatch and store feedback must be connected before releasing this source order / 店铺订单需接通快递发运和平台回传后才能确认发货");
        boolean forward = Set.of("allocate", "pick", "despatch", "release").contains(action);
        if(integrated(order)&&Set.of("allocate","pick","release").contains(action))checkSource(order,id,action);
        Context context = context(order, forward); String operation = UUID.randomUUID().toString();
        if(integrated(order)&&forward)sourceGuard.validateClient(order,context.client());
        order.put("id", id);
        order.put("scanRequired", work.required(order, context.client()));
        switch (action) {
            case "allocate" -> { require(state, "NEW"); stock.allocate(order, context.client(), context.products(), operation); order.put("status", "ALLOCATED"); order.put("allocatedAt", now()); }
            case "pick" -> { require(state, "ALLOCATED"); stock.validate(order, context.client(), context.products(), true); if (work.required(order, context.client())) work.assertScanned(id, stock.view(id)); order.put("status", "PICKED"); order.put("pickedAt", now()); }
            case "cancel" -> {
                if (Set.of("DESPATCHED", "CANCELLED").contains(state)) throw WarehouseException.conflict("Order cannot be cancelled in its current state");
                stock.releaseOrConsume(order, context.client(), context.products(), operation, false); work.cancel(id); order.put("status", "CANCELLED"); order.put("cancelledAt", now());
            }
            case "hold" -> { require(state, "NEW"); if (stock.hasHistory(id)) throw WarehouseException.conflict("Order has allocation history"); order.put("status", "HOLDING"); }
            case "release" -> { require(state, "HOLDING"); if (stock.hasHistory(id)) throw WarehouseException.conflict("Held order has allocation history"); order.put("status", "NEW"); }
            case "despatch" -> {
                require(state, "PICKED"); stock.validate(order, context.client(), context.products(), true);
                work.despatch(order, context.client(), stock.view(id));
                order.put("despatchedAt", now()); order.put("tracking", text(payload, "tracking")); order.put("status", "DESPATCHED");
                charge(order, context); stock.releaseOrConsume(order, context.client(), context.products(), operation, true);
                discontinueEmptyProducts(context);
            }
            default -> throw WarehouseException.invalid("Unsupported order action");
        }
        order.put("lastOperationId", operation); order.remove("id");
        Map<String, Object> saved = records.write("orders", id, order, ((Number) before.get("_version")).longValue(), natural(order), text(order, "client"));
        records.audit((integrated(order)?"INTEGRATED_ORDER_":"MANUAL_ORDER_") + action.toUpperCase(Locale.ROOT), "orders", id, before, Map.of("order", saved, "allocations", stock.view(id), "operationId", operation));
        return complete(requestId, hash, view(saved));
    }

    @Transactional
    public Map<String, Object> workAction(String id, Map<String, Object> input) {
        Map<String,Object> payload = OrderWorkRequest.parse(input); records.scoped("orders", id, false);
        String request = OrderRequest.id(input,"requestId"), hash = OrderRequest.hash(Map.of("id",id,"input",payload));
        var replay = claim(request,"ORDER_WORK",hash); if (replay != null) return replay;
        var before = records.scoped("orders",id,true); nativeOrder(before);
        if(carrierGuard!=null)carrierGuard.available(id,"");
        if (((Number)before.get("_version")).longValue() != WarehouseJson.integer(payload.get("_version"),"_version",1,Long.MAX_VALUE)) throw WarehouseException.conflict("Order changed; reload before continuing / 订单已更新，请刷新后继续");
        var order = business(before); order.put("id",id); String action = text(payload,"action"), state = text(order,"status");
        if (!Set.of("ALLOCATED","PICKED").contains(state)) throw WarehouseException.conflict("Scanning and packing require an allocated or picked order / 请先分配库存，已关闭订单不能继续作业");
        boolean forward = Set.of("scan","pack").contains(action);
        if(forward&&integrated(order))checkSource(order,id,action);
        Context context = context(order,forward);
        if(forward&&integrated(order))sourceGuard.validateClient(order,context.client());
        stock.validate(order,context.client(),context.products(),forward); var allocations = stock.view(id);
        order.put("scanRequired",work.required(order,context.client()));
        switch(action) {
            case "scan" -> { require(state,"ALLOCATED"); work.scan(order,allocations,payload); order.put("scanRequired",true); }
            case "unscan" -> { work.unscan(order,payload); order.put("status","ALLOCATED"); order.remove("pickedAt"); }
            case "pack" -> { require(state,"PICKED"); work.pack(order,allocations,payload); order.put("scanRequired",true); }
            case "unpack" -> work.unpack(order,payload);
            case "reopenpick" -> { require(state,"PICKED"); work.requireNoPackages(id); order.put("status","ALLOCATED"); order.remove("pickedAt"); }
            default -> throw WarehouseException.invalid("Unsupported work action");
        }
        order.remove("id");var saved = records.write("orders",id,order,((Number)before.get("_version")).longValue(),natural(order),text(order,"client"));
        records.audit("ORDER_WORK_"+action.toUpperCase(Locale.ROOT),"orders",id,before,saved);
        return complete(request,hash,view(saved));
    }

    /** Internal carrier entry point. Caller holds an order lease; this validates and locks the actual packed work. */
    @Transactional
    public Map<String,Object> carrierSnapshot(String id,String ownedShipment,String action){
        if(!Set.of("warehouse:carriers:services","warehouse:carriers:book","warehouse:carriers:recover").contains(action))throw WarehouseException.forbidden();
        var order=records.scoped("orders",id,true);nativeOrder(order);require(text(order,"status"),"PICKED");
        if(carrierGuard==null)unsupported("Carrier order protection is unavailable");carrierGuard.available(id,ownedShipment);
        if(integrated(order))checkSource(order,id,action);
        carrierGuard.authorize(action,order);
        var context=context(order,true);if(integrated(order))sourceGuard.validateClient(order,context.client());
        for(var product:context.products().values())for(String field:List.of("unNumber","hazardousUnNumber","hazardousClass","properShippingName","packingCode","tunnelCode","limitedQuantity","fullProperShippingCode","contentId","hazardousPackagingDescription","hazardousPackagingInstructions","hazardousNotes","litresPerUnit","freezingPoint")){
            Object value=product.get(field);if(value!=null&&!value.toString().isBlank()&&!Set.of("0","0.0","0.00","false").contains(value.toString()))throw new com.ruoyi.warehouse.carriers.CarrierFailure("DPD_DANGEROUS_GOODS_UNSUPPORTED");
        }
        stock.validate(order,context.client(),context.products(),true);var allocations=stock.view(id);work.assertScanned(id,allocations);var packing=work.view(id,allocations,true);
        if(!Boolean.TRUE.equals(packing.get("packingComplete")))throw WarehouseException.conflict("Seal every parcel before requesting a carrier label / 申请面单前请完成全部装箱");
        var packages=WarehouseJson.maps(packing.get("packages")).stream().filter(p->"SEALED".equals(p.get("state"))).sorted(Comparator.comparing(p->text(p,"id"))).toList();
        if(packages.isEmpty())throw WarehouseException.conflict("No sealed packages / 没有已封箱包裹");
        var result=new LinkedHashMap<String,Object>();for(String key:List.of("id","_version","client","warehouse","reference","name","companyName","address1","address2","address3","town","county","postcode","country","phone","email","workflow"))result.put(key,order.getOrDefault(key,""));
        result.put("parcels",packages.stream().map(p->{var row=new LinkedHashMap<String,Object>();for(String key:List.of("id","packageCode","weightKg","lengthCm","widthCm","heightCm"))row.put(key,p.get(key));return row;}).toList());return result;
    }

    private Context context(Map<String, Object> order, boolean forward) {
        checkScope(order); Map<String, Map<String, Object>> products = new TreeMap<>();
        List<Map<String, Object>> lines = WarehouseJson.maps(order.get("lines")); if (lines.isEmpty()) throw WarehouseException.invalid("Order has no physical product lines");
        for (String productId : lines.stream().map(line -> text(line, "product")).sorted().toList()) {
            Map<String, Object> product = records.scoped("products", productId, true);
            if (!text(order, "client").equals(product.get("client"))) throw WarehouseException.invalid("Order product belongs to another client");
            if (forward && (enabled(product, "bundle") || enabled(product, "dropship") || enabled(product, "infiniteStock") || enabled(product, "discontinued") || inactive(product))) unsupported("Only active ordinary physical products are supported by the manual order workflow");
            products.put(productId, product);
        }
        Map<String, Object> client = records.get("clients", text(order, "client"), true), warehouse = records.get("warehouses", text(order, "warehouse"), true);
        if (forward) {
            if (inactive(client) || enabled(client, "onStop") || inactive(warehouse)) throw WarehouseException.conflict("Client is stopped/inactive or warehouse is inactive / 客户已停用、暂停或仓库未启用");
            if (enabled(client, "infiniteStock") || enabled(client, "requireRework")) unsupported("Infinite stock and mandatory rework are not enabled for native manual orders yet");
            List<Map<String, Object>> restrictions = currentKind("productWarehouses");
            for (Map<String, Object> restriction : restrictions) if (!inactive(restriction) && products.containsKey(text(restriction, "product"))) {
                String product = text(restriction, "product"); boolean allowed = restrictions.stream().anyMatch(row -> !inactive(row) && product.equals(row.get("product")) && order.get("warehouse").equals(row.get("warehouse")));
                if (!allowed) throw WarehouseException.conflict("Product is not permitted in this warehouse");
            }
        }
        return new Context(client, products);
    }

    private void charge(Map<String, Object> order, Context context) {
        String clientId = text(order, "client");
        List<Map<String, Object>> channelTiers = records.clientRecordsCurrent("channelTiers", clientId).stream().filter(rule -> Objects.equals(order.get("channel"), rule.get("channel"))).toList();
        List<Map<String, Object>> orderTiers = records.clientRecordsCurrent("orderTiers", clientId);
        List<Map<String, Object>> overrides = records.clientRecordsCurrent("warehousePrices", clientId).stream().filter(rule -> Objects.equals(order.get("warehouse"), rule.get("warehouse")) && (text(rule, "product").isEmpty() || context.products().containsKey(text(rule, "product")))).toList();
        List<Map<String, Object>> channelOrders = channelTiers.stream().filter(rule -> !inactive(rule) && "Orders".equals(rule.get("basis"))).toList();
        List<Map<String, Object>> clientOrders = orderTiers.stream().filter(rule -> !inactive(rule)).toList();
        if (channelOrders.size() > 1 || clientOrders.size() > 1) throw WarehouseException.conflict("Multiple active order-count pricing rules match this order");
        Map<String, Object> periodRule = !channelOrders.isEmpty() ? channelOrders.get(0) : clientOrders.isEmpty() ? null : clientOrders.get(0);
        Map<String, Object> quoteInput = new LinkedHashMap<>(); quoteInput.put("client", context.client()); quoteInput.put("order", order); quoteInput.put("products", new ArrayList<>(context.products().values())); quoteInput.put("overrides", overrides); quoteInput.put("channelTiers", channelTiers);
        if (periodRule != null) quoteInput.put("excludePicking", true);
        Map<String, Object> quote;
        try { quote = BillingCalculator.orderQuote(quoteInput); } catch (IllegalArgumentException invalid) { throw WarehouseException.conflict("Order pricing requires correction: " + invalid.getMessage()); }
        BigDecimal amount = new BigDecimal(quote.get("amount").toString());
        if ((long) amount.precision() - amount.scale() > 22) throw WarehouseException.conflict("Order charge exceeds the accounting amount limit; correct pricing before despatch / 订单费用超出财务金额上限，请先修正价格");
        String periodId = periodRule == null ? null : periods.registerOrder(order, context.client(), periodRule);
        String id = text(order, "id"), chargeId = UUID.randomUUID().toString(), source = (periodRule == null ? "order:" : "order-extras:") + id; Map<String, Object> charge = new LinkedHashMap<>();
        charge.put("client", order.get("client")); charge.put("warehouse", order.get("warehouse")); charge.put("channel", order.get("channel")); charge.put("description", (periodRule == null ? "Picking " : "Order extras ") + text(order, "reference")); charge.put("reference", order.get("reference")); charge.put("type", periodRule == null ? "Picking" : "Additional");
        charge.put("currency", text(context.client(), "currency").isEmpty() ? "GBP" : text(context.client(), "currency")); charge.put("orderIds", List.of(id)); charge.put("amount", quote.get("amount")); charge.put("breakdown", quote.get("lines")); charge.put("source", source); charge.put("status", "UNCONFIRMED");
        var saved = records.write("charges", chargeId, charge, null, source, text(order, "client"));
        records.replaceReferences(chargeId, Map.of("order", id, "client", text(order, "client"), "warehouse", text(order, "warehouse")));
        Map<String, Object> snapshot = new LinkedHashMap<>(Map.of("version", 1, "recordedAt", now(), "context", quoteInput));
        if (periodId != null) snapshot.put("period", Map.of("id", periodId, "ruleId", periodRule.get("id")));
        jdbc.update("INSERT INTO wh_order_billing(order_id,charge_id,source_snapshot,quote_json) VALUES(?,?,?,?)", id, chargeId, WarehouseJson.write(snapshot), WarehouseJson.write(quote));
        records.audit("ASSESS_MANUAL_ORDER", "charges", chargeId, null, saved); order.put("billingStatus", periodRule == null ? "CHARGED" : "TIER_PENDING"); order.put("billingChargeId", chargeId);
        if (periodId != null) order.put("billingPeriodId", periodId);
    }

    private void discontinueEmptyProducts(Context context) {
        if (!enabled(context.client(), "discontinueZero")) return;
        for (Map<String, Object> product : context.products().values()) {
            String id = text(product, "id");
            // Product parents are already locked. Read every warehouse currently,
            // not a repeatable-read snapshot from before waiting for those locks.
            List<Long> quantities = jdbc.query("SELECT on_hand FROM wh_inventory WHERE product_id=? ORDER BY id FOR UPDATE", (rs, n) -> rs.getLong(1), id);
            if (quantities.stream().anyMatch(quantity -> quantity > 0) || enabled(product, "discontinued")) continue;
            Map<String, Object> updated = business(product); updated.put("discontinued", true);
            var saved = records.write("products", id, updated, ((Number) product.get("_version")).longValue(), text(product, "sku").toLowerCase(Locale.ROOT), text(product, "client"));
            records.audit("DISCONTINUE_ZERO_STOCK", "products", id, product, saved);
        }
    }

    private Map<String, Object> claim(String request, String type, String hash) {
        jdbc.update("INSERT INTO wh_operation_request(actor_id,request_id,operation_type,request_hash,response_json) VALUES(?,?,?,?,?) ON DUPLICATE KEY UPDATE request_id=request_id", scope.actor(), request, type, hash, "{}");
        return jdbc.queryForObject("SELECT operation_type,request_hash,response_json FROM wh_operation_request WHERE actor_id=? AND request_id=? FOR UPDATE", (rs, n) -> {
            if (!type.equals(rs.getString(1)) || !hash.equals(rs.getString(2))) throw WarehouseException.conflict("requestId was already used for another operation / 请求编号已用于其他操作");
            Map<String, Object> response = WarehouseJson.map(rs.getString(3)); return response.isEmpty() ? null : response;
        }, scope.actor(), request);
    }
    private Map<String, Object> complete(String request, String hash, Map<String, Object> result) { if (jdbc.update("UPDATE wh_operation_request SET response_json=? WHERE actor_id=? AND request_id=? AND request_hash=?", WarehouseJson.write(result), scope.actor(), request, hash) != 1) throw WarehouseException.conflict("Order request journal changed"); return result; }
    private List<Map<String, Object>> currentKind(String kind) { return jdbc.query("SELECT id FROM wh_record WHERE kind=? ORDER BY id FOR UPDATE", (rs, n) -> rs.getString(1), kind).stream().map(id -> records.get(kind, id, true)).toList(); }
    private Map<String, Object> view(Map<String, Object> order) {
        var result = WarehouseJson.copy(order); var allocations = stock.view(text(order,"id")); result.put("allocations",allocations);
        result.put("scanRequired",work.required(order,records.get("clients",text(order,"client"),false)));
        result.put("work",work.view(text(order,"id"),allocations,TransactionSynchronizationManager.isActualTransactionActive()&&!TransactionSynchronizationManager.isCurrentTransactionReadOnly()));
        if(carrierGuard!=null)result.put("carrierShipment",carrierGuard.summary(text(order,"id")));
        return result;
    }
    private static Map<String, Object> business(Map<String, Object> value) { var result = WarehouseJson.copy(value); for (String field : List.of("id", "_version", "createdAt", "updatedAt", "allocations")) result.remove(field); return result; }
    private void checkScope(Map<String, Object> order) { if (!scope.granted("CLIENT", text(order, "client")) || !scope.granted("WAREHOUSE", text(order, "warehouse"))) throw WarehouseException.forbidden(); }
    private static String natural(Map<String, Object> order) { return text(order, "reference").toLowerCase(Locale.ROOT); }
    private static void require(String state, String required) { if (!required.equals(state)) throw WarehouseException.conflict("Order action requires status " + required + " / 当前订单状态不允许此操作"); }
    private static boolean integrated(Map<String,Object> order){return "JAVA_WOO_V1".equals(order.get("workflow"))&&"WOOCOMMERCE".equals(order.get("source"))&&"INTEGRATION".equals(order.get("shippingMode"))&&!text(order,"integrationConnection").isEmpty()&&!text(order,"integrationInbox").isEmpty();}
    private void checkSource(Map<String,Object> order,String id,String action){if(sourceGuard==null)unsupported("Source verification is unavailable");order.put("id",id);sourceGuard.check(order,action);}
    private static void nativeOrder(Map<String, Object> order) { if(integrated(order))return;if (!"JAVA_MANUAL_V1".equals(order.get("workflow")) || !"MANUAL".equals(order.get("source")) || (text(order,"channelId").isEmpty()&&!"Manual".equals(order.get("channel"))) || !"MANUAL".equals(order.get("shippingMode"))) unsupported("This order needs its original integration or advanced workflow"); }
    private static void unsupported(String message) { throw new WarehouseException(409, "warehouse_order_workflow_unavailable", message); }
    private static String now() { return Instant.now().truncatedTo(ChronoUnit.MICROS).toString(); }
    private static String text(Map<String, Object> row, String key) { return WarehouseJson.text(row, key); }
    private static boolean enabled(Map<String, Object> row, String key) { return WarehouseJson.enabled(row.get(key)); }
    private static boolean inactive(Map<String, Object> row) { return Boolean.FALSE.equals(row.get("active")) || "N".equals(row.get("active")); }
    private record Context(Map<String, Object> client, Map<String, Map<String, Object>> products) { }
}
