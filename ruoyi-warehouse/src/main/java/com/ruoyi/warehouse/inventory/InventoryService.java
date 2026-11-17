package com.ruoyi.warehouse.inventory;

import com.ruoyi.warehouse.data.WarehouseRepository;
import com.ruoyi.warehouse.security.WarehouseScope;
import com.ruoyi.warehouse.support.WarehouseException;
import com.ruoyi.warehouse.support.WarehouseJson;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Manual physical stock only. Each request, layer change, ledger and audit entry
 * commits together. ASN receiving, outbound reservations and billing are separate
 * workflows and must not call this endpoint to bypass their business evidence.
 */
@Service
public class InventoryService {
    private static final String OPERATION = "MANUAL_INVENTORY";
    private static final long MAX_TOTAL = 9_007_199_254_740_991L;
    private final WarehouseRepository records;
    private final WarehouseScope scope;
    private final JdbcTemplate jdbc;

    public InventoryService(WarehouseRepository records, WarehouseScope scope) {
        this.records = records; this.scope = scope; this.jdbc = records.jdbc();
    }

    /** Internal stock primitive, joined to an ASN receipt transaction, never a manual HTTP action. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Map<String, Object> receiveAsn(String asnId, String receiptId, InventoryMovement request) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("ASN receiving requires its owning transaction");
        var asn = records.scoped("asns", asnId, true);
        if (!"JAVA_ASN_V1".equals(asn.get("workflow")) || !Set.of("NEW", "PARTIALLY_RECEIVED").contains(asn.get("status")) || !"add".equals(request.action()) || !request.warehouse().equals(asn.get("warehouse"))) throw WarehouseException.conflict("Invalid ASN stock context");
        if (jdbc.queryForObject("SELECT COUNT(*) FROM wh_asn_receipt WHERE id=? AND asn_id=?", Long.class, receiptId, asnId) != 1) throw WarehouseException.conflict("ASN receipt evidence is required");
        var product = records.scoped("products", request.product(), true);
        String clientId = WarehouseJson.text(product, "client"); checkScope(clientId, request);
        if (!clientId.equals(asn.get("client"))) throw WarehouseException.forbidden();
        var client = records.get("clients", clientId, true);
        if (inactive(product) || inactive(client) || enabled(client,"onStop") || enabled(product,"bundle") || enabled(product,"dropship") || enabled(product,"infiniteStock") || enabled(client,"infiniteStock")) throw WarehouseException.conflict("ASN requires active physical stock / 收货需要有效的实物商品和客户");
        request.validateTracking(product);
        var warehouse = records.get("warehouses", request.warehouse(), true);
        var location = records.get("locations", request.location(), true);
        StocktakeLock.available(jdbc, clientId, request.warehouse(), request.location());
        validateLocation(location, warehouse, clientId, true); capacity(location, request.quantity()); serialAvailable(request, null);
        Pool target = findPool(request, request.warehouse(), request.location(), true, clientId); assertLayers(target);
        String layerId = UUID.randomUUID().toString(); Timestamp received = Timestamp.from(Instant.now().truncatedTo(ChronoUnit.MICROS));
        addLayer(target, layerId, layerId, received, request.quantity()); writePool(target, request.quantity());
        jdbc.update("INSERT INTO wh_ledger(operation_id,inventory_id,layer_id,quantity_delta,reserved_delta,reference_id,reason,actor_id) VALUES(?,?,?,?,0,?,?,?)", receiptId, target.id(), layerId, request.quantity(), asnId, "ASN receipt " + receiptId, scope.actor());
        assertLayers(readPool(target.id()));
        return Map.of("inventoryId", target.id(), "layerId", layerId, "receivedAt", received.toInstant().toString());
    }

    /** Approved receipt-record correction. Never used for disposal or supplier handover. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void correctReceipt(String correction,Map<String,Object> asn,String product,List<Map<String,Object>> allocations,String reason){
        if(!TransactionSynchronizationManager.isActualTransactionActive()||jdbc.queryForObject("SELECT COUNT(*) FROM wh_asn_correction WHERE id=? AND asn_id=? AND status='APPROVED'",Long.class,correction,asn.get("id"))!=1)throw WarehouseException.conflict("Approved correction required");
        records.scoped("products",product,true);String client=WarehouseJson.text(asn,"client");records.scoped("clients",client,true);
        var warehouses=new TreeSet<String>();var locations=new TreeSet<String>();for(var row:allocations){warehouses.add(WarehouseJson.text(row,"warehouse"));locations.add(WarehouseJson.text(row,"location"));}
        for(String warehouse:warehouses)records.scoped("warehouses",warehouse,true);for(String location:locations)records.scoped("locations",location,true);
        for(var row:allocations){String layerId=WarehouseJson.text(row,"layer");long quantity=WarehouseJson.integer(row.get("quantity"),"quantity",1,10000000);Pool pool=readPool(WarehouseJson.text(row,"inventory"));
            if(!pool.product().equals(product)||!pool.client().equals(client)||!pool.warehouse().equals(row.get("warehouse"))||!pool.location().equals(row.get("location")))throw WarehouseException.conflict("Correction source ownership changed / 更正来源归属已变化");
            if(!scope.granted("WAREHOUSE",pool.warehouse()))throw WarehouseException.forbidden();StocktakeLock.available(jdbc,client,pool.warehouse(),pool.location());var layers=assertLayers(pool);
            var layer=layers.stream().filter(l->layerId.equals(l.id())).findFirst().orElseThrow(WarehouseException::missing);
            if(layer.remaining()-layer.reserved()<quantity)throw WarehouseException.conflict("Correction cannot remove reserved or dispatched goods / 更正不能扣除已预留或已发货数量");
            if(jdbc.update("UPDATE wh_stock_layer SET remaining_quantity=remaining_quantity-? WHERE id=? AND remaining_quantity-reserved_quantity>=?",quantity,layerId,quantity)!=1)throw WarehouseException.conflict("Correction stock changed");
            writePool(pool,-quantity);returnLedger(correction,pool.id(),layerId,-quantity,WarehouseJson.text(asn,"id"),"Receipt record correction: "+reason);assertLayers(readPool(pool.id()));
        }
    }

    /** Custody is real, capacity-checked stock, but never available to picking. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void changeUnidentified(Map<String,Object> asn,String location,long delta){
        String client=WarehouseJson.text(asn,"client"),warehouse=WarehouseJson.text(asn,"warehouse");
        var owner=records.scoped("clients",client,true);var wh=records.scoped("warehouses",warehouse,true);var loc=records.scoped("locations",location,true);
        validateCustody(loc,wh,client,delta>0);StocktakeLock.available(jdbc,client,warehouse,location);
        if(delta>0){if(inactive(owner)||enabled(owner,"onStop")||enabled(owner,"infiniteStock"))throw WarehouseException.conflict("Client cannot receive physical arrivals / 客户不能接收实物到货");capacity(loc,delta);}
        UnidentifiedOccupancy.change(jdbc,location,delta);
    }
    @Transactional(propagation = Propagation.MANDATORY)
    public Map<String,Object> receiveAsnCustody(String asnId,String taskId) {
        var asn=asnCustodyContext(asnId);
        var rows=jdbc.queryForList("SELECT * FROM wh_asn_custody WHERE id=? AND asn_id=? FOR UPDATE",taskId,asnId);
        if(rows.size()!=1)throw WarehouseException.missing();var task=rows.get(0);
        if(task.get("layer_id")!=null)throw WarehouseException.conflict("Custody already received");
        String productId=String.valueOf(task.get("product_id")),client=WarehouseJson.text(asn,"client"),warehouse=WarehouseJson.text(asn,"warehouse"),location=String.valueOf(task.get("location_id"));
        var product=records.scoped("products",productId,true);if(!client.equals(product.get("client")))throw WarehouseException.forbidden();
        var owner=records.get("clients",client,true);var wh=records.get("warehouses",warehouse,true);var loc=records.get("locations",location,true);
        if(inactive(product)||inactive(owner)||enabled(owner,"onStop")||enabled(owner,"infiniteStock")||enabled(product,"bundle")||enabled(product,"dropship")||enabled(product,"infiniteStock"))throw WarehouseException.conflict("ASN custody requires active physical products");
        long quantity=((Number)task.get("quantity")).longValue();
        var request=new InventoryMovement(taskId,"add",productId,warehouse,location,quantity,"ASN custody",String.valueOf(task.get("batch_number")),String.valueOf(task.get("serial_number")),String.valueOf(task.get("expiry_date")),"","");
        request.validateTracking(product);validateCustody(loc,wh,client,true);StocktakeLock.available(jdbc,client,warehouse,location);capacity(loc,quantity);serialAvailable(request,null);
        Pool target=findPool(request,warehouse,location,true,client);assertLayers(target);
        String layer=UUID.randomUUID().toString(),origin=layer;Timestamp received=Timestamp.from(Instant.now().truncatedTo(ChronoUnit.MICROS));
        var arrivals=jdbc.query("SELECT a.id,a.arrived_at,a.asn_id,a.location_id,e.quantity,p.product_id FROM wh_asn_unidentified_event e JOIN wh_asn_unidentified a ON a.id=e.arrival_id JOIN wh_asn_identity_approval p ON p.id=e.approval_id WHERE e.custody_id=? AND e.action_name='unknownExecute' AND p.decision='identify'",(rs,n)->Map.<String,Object>of("id",rs.getString(1),"at",rs.getTimestamp(2),"asn",rs.getString(3),"location",rs.getString(4),"quantity",rs.getLong(5),"product",rs.getString(6)),taskId);
        if(!arrivals.isEmpty()){var source=arrivals.get(0);if(!asnId.equals(source.get("asn"))||!location.equals(source.get("location"))||!productId.equals(source.get("product"))||quantity!=((Number)source.get("quantity")).longValue())throw WarehouseException.conflict("Identification source does not match custody / 认领来源与保管实物不一致");origin=String.valueOf(source.get("id"));received=(Timestamp)source.get("at");}
        addLayer(target,layer,origin,received,quantity);writePool(target,quantity);returnLedger(taskId,target.id(),layer,quantity,asnId,"ASN custody received");assertLayers(readPool(target.id()));
        return Map.of("inventoryId",target.id(),"layerId",layer,"originId",origin,"receivedAt",received.toInstant().toString());
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Map<String,Object> disposeAsnCustody(String asnId,String eventId) {
        var asn=asnCustodyContext(asnId);
        var rows=jdbc.queryForList("SELECT e.*,c.product_id,c.layer_id,c.location_id custody_location,c.quantity receipt_quantity,c.disposed_quantity,c.inspection FROM wh_asn_custody_event e JOIN wh_asn_custody c ON c.id=e.custody_id WHERE e.id=? AND c.asn_id=? FOR UPDATE",eventId,asnId);
        if(rows.size()!=1)throw WarehouseException.missing();var event=rows.get(0);
        boolean putaway="putaway".equals(event.get("action_name")),supplierReturn="returnSupplier".equals(event.get("action_name"));long quantity=((Number)event.get("quantity")).longValue();
        if(event.get("result_json")!=null||!Set.of("putaway","scrap","returnSupplier").contains(event.get("action_name"))||!supplierReturn&&!(putaway?"PASS":"FAIL").equals(event.get("inspection"))||quantity<=0||quantity>((Number)event.get("receipt_quantity")).longValue()-((Number)event.get("disposed_quantity")).longValue())throw WarehouseException.conflict("Inspection or custody quantity does not permit this action / 检验结果或待处理数量不允许此操作");
        if(supplierReturn){
            var approvals=jdbc.queryForList("SELECT quantity-handed_quantity-cancelled_quantity remaining FROM wh_asn_return_approval WHERE id=? AND custody_id=? FOR UPDATE",event.get("return_approval_id"),event.get("custody_id"));
            if(approvals.size()!=1||((Number)approvals.get(0).get("remaining")).longValue()<quantity||WarehouseJson.text(event,"handover_reference").isBlank())throw WarehouseException.conflict("Supplier return needs an unused approval and handover evidence / 退供需要有效批准和实际交接凭据");
        }
        String client=WarehouseJson.text(asn,"client"),warehouse=WarehouseJson.text(asn,"warehouse"),from=String.valueOf(event.get("custody_location")),to=putaway?String.valueOf(event.get("location_id")):from;
        var product=records.scoped("products",String.valueOf(event.get("product_id")),true);if(!client.equals(product.get("client")))throw WarehouseException.forbidden();var owner=records.get("clients",client,true);var wh=records.get("warehouses",warehouse,true);
        var locations=new HashMap<String,Map<String,Object>>();for(String loc:new TreeSet<>(List.of(from,to)))locations.put(loc,records.get("locations",loc,true));
        validateCustody(locations.get(from),wh,client,false);StocktakeLock.available(jdbc,client,warehouse,from);
        var layer=jdbc.queryForMap("SELECT * FROM wh_stock_layer WHERE id=? FOR UPDATE",event.get("layer_id"));Pool held=readPool(String.valueOf(layer.get("inventory_id")));assertLayers(held);
        if(!held.product().equals(event.get("product_id"))||!held.client().equals(client)||!held.warehouse().equals(warehouse)||!held.location().equals(from)||((Number)layer.get("reserved_quantity")).longValue()!=0||((Number)layer.get("remaining_quantity")).longValue()!=((Number)event.get("receipt_quantity")).longValue()-((Number)event.get("disposed_quantity")).longValue())throw WarehouseException.conflict("ASN custody source changed; reconcile first / 入库保管来源已变化，请先核对");
        Timestamp original=(Timestamp)layer.get("received_at");String origin=String.valueOf(layer.get("origin_id")),targetPool="",targetLayer="";
        if(putaway){
            if(inactive(product)||inactive(owner)||enabled(owner,"onStop")||enabled(owner,"infiniteStock")||enabled(product,"bundle")||enabled(product,"dropship")||enabled(product,"infiniteStock"))throw WarehouseException.conflict("Product or client cannot receive saleable stock");
            if(!held.expiry().isEmpty()&&java.time.LocalDate.parse(held.expiry()).isBefore(java.time.LocalDate.now(java.time.ZoneOffset.UTC)))throw WarehouseException.conflict("Expired inbound goods cannot be put away / 过期入库货物不能转为可售库存");
            validateLocation(locations.get(to),wh,client,true);StocktakeLock.available(jdbc,client,warehouse,to);capacity(locations.get(to),quantity);
            var movement=new InventoryMovement(eventId,"add",held.product(),warehouse,to,quantity,"ASN putaway",held.batch(),held.serial(),held.expiry(),"","");movement.validateTracking(product);serialAvailable(movement,held.id());
            Pool target=findPool(movement,warehouse,to,true,client);assertLayers(target);targetPool=target.id();targetLayer=UUID.randomUUID().toString();
            addLayer(target,targetLayer,origin,original,quantity);writePool(target,quantity);returnLedger(eventId,targetPool,targetLayer,quantity,asnId,"ASN putaway");assertLayers(readPool(targetPool));
        }
        if(jdbc.update("UPDATE wh_stock_layer SET remaining_quantity=remaining_quantity-? WHERE id=? AND remaining_quantity>=? AND reserved_quantity=0",quantity,event.get("layer_id"),quantity)!=1)throw WarehouseException.conflict("Custody layer changed");
        writePool(held,-quantity);returnLedger(eventId,held.id(),String.valueOf(event.get("layer_id")),-quantity,asnId,(putaway?"ASN released: ":supplierReturn?"ASN supplier handover: ":"ASN scrapped: ")+event.get("reason"));assertLayers(readPool(held.id()));
        return Map.of("inventoryId",targetPool,"layerId",targetLayer,"originId",origin,"receivedAt",original.toInstant().toString(),"quantity",quantity);
    }
    private Map<String,Object> asnCustodyContext(String id){
        if(!TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("ASN custody requires its owning transaction");
        var head=records.scoped("asns",id,true);if(!"JAVA_ASN_V1".equals(head.get("workflow"))||!Set.of("NEW","PARTIALLY_RECEIVED","RECEIVED").contains(head.get("status")))throw WarehouseException.conflict("Invalid ASN custody context");return head;
    }

    public Map<String, Object> list(int page, int size, String client, String product, String warehouse, String keyword) {
        if (page < 1 || page > 100000 || size < 1 || size > 200) throw WarehouseException.invalid("Invalid inventory pagination");
        List<Object> args = new ArrayList<>(); String where = scope.inventoryPredicate(args);
        String[] fields = {"i.client_id", "i.product_id", "i.warehouse_id"}; String[] filters = {client, product, warehouse};
        for (int i = 0; i < fields.length; i++) if (filters[i] != null && !filters[i].isBlank()) {
            if (filters[i].length() > 64) throw WarehouseException.invalid("Inventory filter is too long");
            where += " AND " + fields[i] + "=?"; args.add(filters[i]);
        }
        String search = keyword == null ? "" : keyword.strip();
        if (search.length() > 200) throw WarehouseException.invalid("Search text too long");
        if (!search.isEmpty()) {
            where += " AND (LOCATE(?,p.display_name)>0 OR LOCATE(?,i.batch_number)>0 OR LOCATE(?,i.serial_number)>0 OR LOCATE(?,l.display_name)>0)";
            for (int i = 0; i < 4; i++) args.add(search);
        }
        String joins = " FROM wh_inventory i JOIN wh_record p ON p.id=i.product_id JOIN wh_record c ON c.id=i.client_id JOIN wh_record w ON w.id=i.warehouse_id JOIN wh_record l ON l.id=i.location_id LEFT JOIN wh_stocktake_lock f ON f.client_id=i.client_id AND f.warehouse_id=i.warehouse_id AND f.location_id=i.location_id WHERE " + where;
        Long total = jdbc.queryForObject("SELECT COUNT(*)" + joins, Long.class, args.toArray());
        args.add(size); args.add((long) (page - 1) * size);
        var rows = jdbc.query("SELECT i.*,f.stocktake_id,p.business_json product_json,l.business_json location_json,c.display_name client_name,w.display_name warehouse_name,l.display_name location_name,(SELECT MIN(s.received_at) FROM wh_stock_layer s WHERE s.inventory_id=i.id AND s.remaining_quantity>0) oldest_received" + joins + " ORDER BY p.display_name,w.display_name,l.display_name,i.id LIMIT ? OFFSET ?", (rs, n) -> {
            Map<String, Object> row = pool(rs).view(); Map<String, Object> p = WarehouseJson.map(rs.getString("product_json"));
            row.put("unreserved",row.get("available"));row.put("stocktakeId",rs.getString("stocktake_id"));if(rs.getString("stocktake_id")!=null)row.put("available",0L);
            var loc=WarehouseJson.map(rs.getString("location_json"));boolean held=enabled(loc,"custodyOnly")||"Quarantine".equals(loc.get("type"));row.put("quarantined",held);if(held)row.put("available",0L);
            row.put("sku", WarehouseJson.text(p, "sku")); row.put("productName", WarehouseJson.text(p, "name")); row.put("clientName", rs.getString("client_name"));
            row.put("warehouseName", rs.getString("warehouse_name")); row.put("locationName", rs.getString("location_name"));
            Timestamp oldest = rs.getTimestamp("oldest_received"); row.put("oldestReceivedAt", oldest == null ? null : oldest.toInstant().toString()); return row;
        }, args.toArray());
        return Map.of("code", 200, "msg", "success", "total", total == null ? 0 : total, "rows", rows);
    }

    @Transactional
    public Map<String, Object> move(Map<String, Object> input) {
        InventoryMovement request = InventoryMovement.parse(input);
        // Check current scope even for a completed retry; revoked access cannot use
        // the idempotency journal to recover someone else's inventory details.
        Map<String, Object> product = records.scoped("products", request.product(), false);
        String clientId = WarehouseJson.text(product, "client"); checkScope(clientId, request);
        Map<String, Object> replay = claim(request);
        if (replay != null) return replay;

        // A product parent serializes serial uniqueness and stock-pool creation.
        // Location parents, acquired in sorted order, serialize physical capacity
        // across products. All inventory writers must honor this locking contract.
        product = records.scoped("products", request.product(), true);
        clientId = WarehouseJson.text(product, "client"); checkScope(clientId, request);
        Map<String, Object> client = records.get("clients", clientId, true);
        if (enabled(product, "bundle") || enabled(product, "dropship") || enabled(product, "infiniteStock") || enabled(client, "infiniteStock")) throw WarehouseException.invalid("Virtual or infinite-stock products cannot have manual physical movements");
        request.validateTracking(product);
        Map<String, Map<String, Object>> warehouses = new HashMap<>(), locations = new HashMap<>();
        SortedSet<String> warehouseIds = new TreeSet<>(List.of(request.warehouse()));
        SortedSet<String> locationIds = new TreeSet<>(List.of(request.location()));
        if ("transfer".equals(request.action())) { warehouseIds.add(request.toWarehouse()); locationIds.add(request.toLocation()); }
        for (String id : warehouseIds) warehouses.put(id, records.get("warehouses", id, true));
        for (String id : locationIds) locations.put(id, records.get("locations", id, true));
        StocktakeLock.available(jdbc, clientId, request.warehouse(), request.location());
        if ("transfer".equals(request.action())) StocktakeLock.available(jdbc, clientId, request.toWarehouse(), request.toLocation());
        validateLocation(locations.get(request.location()), warehouses.get(request.warehouse()), clientId, "add".equals(request.action()));
        if ("transfer".equals(request.action())) validateLocation(locations.get(request.toLocation()), warehouses.get(request.toWarehouse()), clientId, true);

        String operationId = UUID.randomUUID().toString(); List<Map<String, Object>> before = new ArrayList<>();
        List<Map<String, Object>> after = new ArrayList<>(); List<Map<String, Object>> evidence = new ArrayList<>();
        if ("add".equals(request.action())) {
            capacity(locations.get(request.location()), request.quantity());
            serialAvailable(request, null);
            Pool target = findPool(request, request.warehouse(), request.location(), true, clientId);
            before.add(target.view()); assertLayers(target);
            Timestamp received = Timestamp.from(Instant.now().truncatedTo(ChronoUnit.MICROS)); String layerId = UUID.randomUUID().toString();
            addLayer(target, layerId, layerId, received, request.quantity());
            writePool(target, request.quantity()); ledger(operationId, target.id(), layerId, request.quantity(), request);
            evidence.add(layerEvidence(layerId, layerId, received, request.quantity()));
            after.add(readPool(target.id()).view());
        } else {
            Pool source = findPool(request, request.warehouse(), request.location(), false, clientId);
            before.add(source.view()); List<Layer> sourceLayers = assertLayers(source);
            if (source.onhand() - source.reserved() < request.quantity()) throw WarehouseException.conflict("Insufficient available stock; reserved stock cannot be moved / 可用库存不足，不能移动已预留库存");
            if (!request.serial().isEmpty() && source.onhand() != 1) throw WarehouseException.conflict("Serial stock is inconsistent / 串号库存不一致");
            Pool target = null;
            if ("transfer".equals(request.action())) {
                capacity(locations.get(request.toLocation()), request.quantity()); serialAvailable(request, source.id());
                target = findPool(request, request.toWarehouse(), request.toLocation(), true, clientId); before.add(target.view()); assertLayers(target);
            }
            long remaining = request.quantity();
            for (Layer layer : sourceLayers) {
                long quantity = Math.min(remaining, layer.remaining() - layer.reserved()); if (quantity == 0) continue;
                if (jdbc.update("UPDATE wh_stock_layer SET remaining_quantity=remaining_quantity-? WHERE id=? AND remaining_quantity-reserved_quantity>=?", quantity, layer.id(), quantity) != 1) throw WarehouseException.conflict("Stock layer changed");
                ledger(operationId, source.id(), layer.id(), -quantity, request);
                if (target != null) {
                    String destinationLayer = UUID.randomUUID().toString(); addLayer(target, destinationLayer, layer.origin(), layer.received(), quantity);
                    ledger(operationId, target.id(), destinationLayer, quantity, request);
                    evidence.add(layerEvidence(destinationLayer, layer.origin(), layer.received(), quantity));
                } else evidence.add(layerEvidence(layer.id(), layer.origin(), layer.received(), -quantity));
                remaining -= quantity; if (remaining == 0) break;
            }
            if (remaining != 0) throw WarehouseException.conflict("Stock source layers do not cover available quantity");
            writePool(source, -request.quantity()); after.add(readPool(source.id()).view());
            if (target != null) { writePool(target, request.quantity()); after.add(readPool(target.id()).view()); }
        }
        for (Map<String, Object> row : after) assertLayers(readPool(String.valueOf(row.get("id"))));
        Map<String, Object> response = new LinkedHashMap<>(); response.put("operationId", operationId); response.put("requestId", request.requestId());
        response.put("operationType", OPERATION); response.put("action", request.action()); response.put("quantity", request.quantity());
        response.put("inventory", after); response.put("layers", evidence); response.put("billingTriggered", false);
        records.audit("MANUAL_STOCK_" + request.action().toUpperCase(Locale.ROOT), "inventory", operationId, before, Map.of("request", request.canonical(), "result", response));
        if (jdbc.update("UPDATE wh_operation_request SET response_json=? WHERE actor_id=? AND request_id=? AND request_hash=?", WarehouseJson.write(response), scope.actor(), request.requestId(), request.hash()) != 1) throw WarehouseException.conflict("Movement request journal changed");
        return response;
    }

    /** Stocktake approval owns the transaction and its immutable count evidence. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Map<String,Object> reconcileStocktake(String stocktakeId, String lineId) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Stocktake approval requires its owning transaction");
        var head=records.scoped("stocktakes",stocktakeId,true);
        if (!"JAVA_STOCKTAKE_V1".equals(head.get("workflow")) || !"REVIEW".equals(head.get("status"))) throw WarehouseException.conflict("Stocktake is not awaiting approval");
        var lines=jdbc.queryForList("SELECT * FROM wh_stocktake_line WHERE id=? AND stocktake_id=? FOR UPDATE",lineId,stocktakeId);
        if(lines.size()!=1)throw WarehouseException.missing();var line=lines.get(0);
        if(line.get("counted_quantity")==null || line.get("result_json")!=null)throw WarehouseException.conflict("Count is missing or already posted");
        String client=WarehouseJson.text(head,"client"), warehouse=WarehouseJson.text(head,"warehouse"), location=String.valueOf(line.get("location_id"));
        var product=records.scoped("products",String.valueOf(line.get("product_id")),true);
        if(!client.equals(product.get("client")))throw WarehouseException.forbidden();
        records.get("clients",client,true);var wh=records.get("warehouses",warehouse,true);var loc=records.get("locations",location,true);
        if(!stocktakeId.equals(StocktakeLock.owner(jdbc,client,warehouse,location)))throw WarehouseException.conflict("Stocktake freeze is missing");
        long expected=((Number)line.get("expected_quantity")).longValue(), counted=((Number)line.get("counted_quantity")).longValue(), delta=counted-expected;
        if(counted<((Number)line.get("expected_reserved")).longValue())throw WarehouseException.conflict("Count is below reserved stock; resolve orders before recounting / 实盘少于订单预留，请取消本次盘点并处理相关订单后重盘");
        String serial=String.valueOf(line.get("serial_number"));if(!serial.isEmpty()&&counted>1)throw WarehouseException.conflict("Serial counts cannot exceed one");
        var movement=new InventoryMovement(lineId,delta<0?"remove":"add",String.valueOf(line.get("product_id")),warehouse,location,Math.abs(delta),"Stocktake "+stocktakeId,String.valueOf(line.get("batch_number")),serial,String.valueOf(line.get("expiry_date")),"","");
        var pools=jdbc.query("SELECT * FROM wh_inventory WHERE product_id=? AND warehouse_id=? AND location_id=? AND batch_number=? AND serial_number=? AND expiry_date=? FOR UPDATE",POOL,movement.product(),warehouse,location,movement.batch(),serial,movement.expiry());
        Pool pool=pools.isEmpty()?null:pools.get(0);
        if(line.get("inventory_id")==null ? pool!=null : pool==null || !pool.id().equals(line.get("inventory_id")) || pool.revision()!=((Number)line.get("inventory_revision")).longValue() || pool.onhand()!=expected || pool.reserved()!=((Number)line.get("expected_reserved")).longValue())throw WarehouseException.conflict("Frozen stock changed; cancel and recount / 冻结库存已变化，请取消后重新盘点");
        List<Layer> layers=pool==null?List.of():assertLayers(pool);
        if(delta==0)return Map.of("delta",0,"inventoryId",pool==null?"":pool.id(),"billingTriggered",false);
        if(enabled(product,"bundle")||enabled(product,"dropship")||enabled(product,"infiniteStock"))throw WarehouseException.conflict("Only physical stock can be counted");
        validateLocation(loc,wh,client,delta>0);
        if(delta>0){
            movement.validateTracking(product);capacity(loc,delta);serialAvailable(movement,null);
            if(pool==null)pool=findPool(movement,warehouse,location,true,client);
            String layer=UUID.randomUUID().toString();Timestamp at=Timestamp.from(Instant.now().truncatedTo(ChronoUnit.MICROS));
            addLayer(pool,layer,layer,at,delta);
            jdbc.update("INSERT INTO wh_ledger(operation_id,inventory_id,layer_id,quantity_delta,reserved_delta,reference_id,reason,actor_id) VALUES(?,?,?,?,0,?,?,?)",lineId,pool.id(),layer,delta,stocktakeId,"Stocktake surplus",scope.actor());
        }else{
            long remaining=-delta;
            for(var layer:layers){long take=Math.min(remaining,layer.remaining()-layer.reserved());if(take==0)continue;
                if(jdbc.update("UPDATE wh_stock_layer SET remaining_quantity=remaining_quantity-? WHERE id=? AND remaining_quantity-reserved_quantity>=?",take,layer.id(),take)!=1)throw WarehouseException.conflict("Stock layer changed");
                jdbc.update("INSERT INTO wh_ledger(operation_id,inventory_id,layer_id,quantity_delta,reserved_delta,reference_id,reason,actor_id) VALUES(?,?,?,?,0,?,?,?)",lineId,pool.id(),layer.id(),-take,stocktakeId,"Stocktake shortage",scope.actor());
                remaining-=take;if(remaining==0)break;
            }
            if(remaining!=0)throw WarehouseException.conflict("Count would consume reserved stock");
        }
        writePool(pool,delta);var after=readPool(pool.id());assertLayers(after);
        records.audit("STOCKTAKE_ADJUST","stocktakes",stocktakeId,pool.view(),Map.of("lineId",lineId,"reason",line.get("count_reason"),"inventory",after.view()));
        return Map.of("delta",delta,"inventoryId",pool.id(),"operationId",lineId,"billingTriggered",false);
    }

    /** Original allocation attributes and age come from SQL, never from browser input. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Map<String,Object> receiveReturn(String returnId,String receiptId) {
        var head=returnContext(returnId);var rows=jdbc.queryForList("SELECT * FROM wh_return_receipt WHERE id=? AND return_id=? FOR UPDATE",receiptId,returnId);
        if(rows.size()!=1)throw WarehouseException.missing();var receipt=rows.get(0);if(receipt.get("layer_id")!=null)throw WarehouseException.conflict("Return already received");
        var allocation=jdbc.queryForMap("SELECT * FROM wh_order_allocation WHERE id=? FOR UPDATE",receipt.get("allocation_id"));
        if(!"CONSUMED".equals(allocation.get("state"))||!head.get("order").equals(allocation.get("order_id")))throw WarehouseException.conflict("Return needs a consumed shipment allocation");
        Pool source=readPool(String.valueOf(allocation.get("inventory_id")));long quantity=((Number)receipt.get("quantity")).longValue();
        if(!head.get("product").equals(source.product())||!head.get("client").equals(source.client())||!head.get("warehouse").equals(source.warehouse()))throw WarehouseException.conflict("Return source ownership mismatch");
        var layer=jdbc.queryForMap("SELECT * FROM wh_stock_layer WHERE id=? AND inventory_id=? FOR UPDATE",allocation.get("layer_id"),source.id());
        long total=jdbc.queryForList("SELECT quantity FROM wh_return_receipt WHERE allocation_id=? FOR UPDATE",Long.class,receipt.get("allocation_id")).stream().mapToLong(Long::longValue).sum();
        if(total>((Number)allocation.get("quantity")).longValue())throw WarehouseException.conflict("Returned quantity exceeds original source");
        String location=String.valueOf(receipt.get("location_id"));var loc=records.get("locations",location,true);var wh=records.get("warehouses",source.warehouse(),true);
        validateCustody(loc,wh,source.client(),true);StocktakeLock.available(jdbc,source.client(),source.warehouse(),location);capacity(loc,quantity);
        var request=new InventoryMovement(receiptId,"add",source.product(),source.warehouse(),location,quantity,"Return receipt",source.batch(),source.serial(),source.expiry(),"","");
        if(!source.serial().isEmpty()&&quantity!=1)throw WarehouseException.conflict("Serial return quantity must be one");serialAvailable(request,null);
        Pool target=findPool(request,source.warehouse(),location,true,source.client());assertLayers(target);
        String layerId=UUID.randomUUID().toString();Timestamp original=(Timestamp)layer.get("received_at");String origin=String.valueOf(layer.get("origin_id"));
        if(original==null||origin.isBlank()||"null".equals(origin))throw WarehouseException.conflict("Original stock age is missing");
        addLayer(target,layerId,origin,original,quantity);writePool(target,quantity);returnLedger(receiptId,target.id(),layerId,quantity,returnId,"Return received into quarantine");assertLayers(readPool(target.id()));
        return Map.of("inventoryId",target.id(),"layerId",layerId,"originId",origin,"receivedAt",original.toInstant().toString());
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Map<String,Object> disposeReturn(String returnId,String dispositionId) {
        var head=returnContext(returnId);var rows=jdbc.queryForList("SELECT d.*,r.return_id,r.layer_id,r.location_id custody_location,r.quantity receipt_quantity,r.disposed_quantity,r.inspection FROM wh_return_disposition d JOIN wh_return_receipt r ON r.id=d.receipt_id WHERE d.id=? AND r.return_id=? FOR UPDATE",dispositionId,returnId);
        if(rows.size()!=1)throw WarehouseException.missing();var d=rows.get(0);if(d.get("result_json")!=null)throw WarehouseException.conflict("Return disposition already posted");
        boolean restock="restock".equals(d.get("action_name"));long quantity=((Number)d.get("quantity")).longValue();
        if(!(restock?"PASS":"FAIL").equals(d.get("inspection"))||quantity>((Number)d.get("receipt_quantity")).longValue()-((Number)d.get("disposed_quantity")).longValue())throw WarehouseException.conflict("Return inspection or quantity does not permit disposition");
        String warehouse=WarehouseJson.text(head,"warehouse"),client=WarehouseJson.text(head,"client"),from=String.valueOf(d.get("custody_location")),to=restock?String.valueOf(d.get("location_id")):from;
        var locations=new HashMap<String,Map<String,Object>>();for(String loc:new TreeSet<>(List.of(from,to)))locations.put(loc,records.get("locations",loc,true));
        var wh=records.get("warehouses",warehouse,true);validateCustody(locations.get(from),wh,client,false);StocktakeLock.available(jdbc,client,warehouse,from);
        var layer=jdbc.queryForMap("SELECT * FROM wh_stock_layer WHERE id=? FOR UPDATE",d.get("layer_id"));Pool held=readPool(String.valueOf(layer.get("inventory_id")));assertLayers(held);
        if(!held.product().equals(head.get("product"))||!held.client().equals(client)||!held.location().equals(from)||!held.warehouse().equals(warehouse)||((Number)layer.get("reserved_quantity")).longValue()!=0||((Number)layer.get("remaining_quantity")).longValue()!=((Number)d.get("receipt_quantity")).longValue()-((Number)d.get("disposed_quantity")).longValue())throw WarehouseException.conflict("Custody evidence changed; reconcile before disposition / 隔离来源数量不一致，请先核对");
        String targetLayer="",targetPool="";boolean expired=!held.expiry().isEmpty()&&java.time.LocalDate.parse(held.expiry()).isBefore(java.time.LocalDate.now(java.time.ZoneOffset.UTC));Timestamp original=(Timestamp)layer.get("received_at");String origin=String.valueOf(layer.get("origin_id"));
        if(restock){
            var product=records.scoped("products",held.product(),true);var owner=records.get("clients",client,true);
            if(inactive(product)||inactive(owner)||enabled(owner,"onStop")||enabled(owner,"infiniteStock")||enabled(product,"bundle")||enabled(product,"dropship")||enabled(product,"infiniteStock"))throw WarehouseException.conflict("Product or client cannot receive saleable stock / 商品或客户不能接收可售库存");
            String configured=WarehouseJson.text(product,"expiryPolicy"),expiryPolicy=Set.of("Warn","BlockExpired").contains(configured)?configured:"BlockExpired".equals(owner.get("expiryPolicy"))?"BlockExpired":"Warn";
            if(expired&&"BlockExpired".equals(expiryPolicy))throw WarehouseException.conflict("Expiry policy blocks restocking this return / 当前效期策略禁止过期退货上架");
            var restrictions=jdbc.queryForList("SELECT business_json FROM wh_record WHERE kind='productWarehouses' ORDER BY id FOR UPDATE",String.class).stream().map(WarehouseJson::map).filter(r->!inactive(r)&&held.product().equals(r.get("product"))).toList();
            if(!restrictions.isEmpty()&&restrictions.stream().noneMatch(r->warehouse.equals(r.get("warehouse"))))throw WarehouseException.conflict("Product is restricted to another warehouse");
            validateLocation(locations.get(to),wh,client,true);StocktakeLock.available(jdbc,client,warehouse,to);capacity(locations.get(to),quantity);
            var request=new InventoryMovement(dispositionId,"add",held.product(),warehouse,to,quantity,"Inspected return restock",held.batch(),held.serial(),held.expiry(),"","");request.validateTracking(product);serialAvailable(request,held.id());
            Pool target=findPool(request,warehouse,to,true,client);assertLayers(target);targetLayer=UUID.randomUUID().toString();targetPool=target.id();addLayer(target,targetLayer,origin,original,quantity);writePool(target,quantity);returnLedger(dispositionId,target.id(),targetLayer,quantity,returnId,"Inspected return restock");assertLayers(readPool(target.id()));
        }
        if(jdbc.update("UPDATE wh_stock_layer SET remaining_quantity=remaining_quantity-? WHERE id=? AND remaining_quantity>=? AND reserved_quantity=0",quantity,d.get("layer_id"),quantity)!=1)throw WarehouseException.conflict("Custody layer changed");
        writePool(held,-quantity);returnLedger(dispositionId,held.id(),String.valueOf(d.get("layer_id")),-quantity,returnId,(restock?"Return released: ":"Return scrapped: ")+d.get("reason"));assertLayers(readPool(held.id()));
        return Map.of("action",d.get("action_name"),"quantity",quantity,"inventoryId",targetPool,"layerId",targetLayer,"originId",origin,"receivedAt",original.toInstant().toString(),"expiredAtDisposition",expired);
    }
    private Map<String,Object> returnContext(String id){
        if(!TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Return inventory requires its owning transaction");
        var head=records.scoped("returns",id,true);if(!"JAVA_RETURN_V1".equals(head.get("workflow"))||Set.of("CANCELLED","CLOSED").contains(head.get("status")))throw WarehouseException.conflict("Invalid return stock context");records.scoped("products",WarehouseJson.text(head,"product"),true);records.scoped("clients",WarehouseJson.text(head,"client"),true);return head;
    }
    private void validateCustody(Map<String,Object> loc,Map<String,Object> wh,String client,boolean incoming){
        if(!Objects.equals(loc.get("warehouse"),wh.get("id"))||!"Quarantine".equals(loc.get("type"))||!enabled(loc,"custodyOnly"))throw WarehouseException.conflict("Use a dedicated quarantine custody location / 请使用已启用保管标记的隔离库位");
        String owner=WarehouseJson.text(loc,"client");if(!owner.isEmpty()&&!client.equals(owner))throw WarehouseException.forbidden();
        if(incoming&&(inactive(loc)||inactive(wh)))throw WarehouseException.conflict("Receiving custody location must be active");
    }
    private void returnLedger(String operation,String pool,String layer,long delta,String rma,String reason){jdbc.update("INSERT INTO wh_ledger(operation_id,inventory_id,layer_id,quantity_delta,reserved_delta,reference_id,reason,actor_id) VALUES(?,?,?,?,0,?,?,?)",operation,pool,layer,delta,rma,reason.substring(0,Math.min(500,reason.length())),scope.actor());}

    private Map<String, Object> claim(InventoryMovement request) {
        jdbc.update("INSERT INTO wh_operation_request(actor_id,request_id,operation_type,request_hash,response_json) VALUES(?,?,?,?,?) ON DUPLICATE KEY UPDATE request_id=request_id", scope.actor(), request.requestId(), OPERATION, request.hash(), "{}");
        return jdbc.queryForObject("SELECT operation_type,request_hash,response_json FROM wh_operation_request WHERE actor_id=? AND request_id=? FOR UPDATE", (rs, n) -> {
            if (!OPERATION.equals(rs.getString(1)) || !request.hash().equals(rs.getString(2))) throw WarehouseException.conflict("requestId was already used for a different operation / 此请求编号已用于其他操作");
            Map<String, Object> response = WarehouseJson.map(rs.getString(3)); return response.isEmpty() ? null : response;
        }, scope.actor(), request.requestId());
    }

    private void checkScope(String client, InventoryMovement request) {
        if (client.isEmpty() || !scope.granted("CLIENT", client) || !scope.granted("WAREHOUSE", request.warehouse()) || "transfer".equals(request.action()) && !scope.granted("WAREHOUSE", request.toWarehouse())) throw WarehouseException.forbidden();
    }

    private void validateLocation(Map<String, Object> location, Map<String, Object> warehouse, String client, boolean destination) {
        if (!Objects.equals(location.get("warehouse"), warehouse.get("id"))) throw WarehouseException.invalid("Location does not belong to this warehouse / 库位不属于所选仓库");
        // An inactive source can be emptied. Positive stock movements alone need
        // an active warehouse/location, matching the original physical stock rule.
        if (destination && (inactive(location) || inactive(warehouse))) throw WarehouseException.invalid("Destination warehouse and location must be active / 目标仓库和库位必须启用");
        if (enabled(location, "custodyOnly") || "Quarantine".equals(location.get("type"))) throw WarehouseException.invalid("Custody and quarantine stock requires its dedicated workflow / 保管与隔离库存必须使用专门流程");
        String owner = WarehouseJson.text(location, "client"); if (!owner.isEmpty() && !client.equals(owner)) throw WarehouseException.forbidden();
    }

    private void capacity(Map<String, Object> location, long incoming) {
        long maximum = nonnegative(location.get("maxUnits"), "Location capacity");
        Map<String, Object> type = records.all("locationTypes").stream().filter(row -> Objects.equals(row.get("name"), location.get("type"))).findFirst().orElseThrow(() -> WarehouseException.invalid("Location type does not exist"));
        type = records.get("locationTypes", WarehouseJson.text(type, "id"), true);
        if (!Objects.equals(type.get("name"), location.get("type")) || inactive(type)) throw WarehouseException.conflict("Location type changed or is inactive");
        if (maximum == 0) maximum = nonnegative(type.get("maxUnits"), "Location type capacity");
        // A parent lock alone is insufficient under MySQL REPEATABLE READ:
        // ordinary SUM would reuse a snapshot created before waiting for it.
        List<Long> occupiedRows = jdbc.query("SELECT on_hand FROM wh_inventory WHERE location_id=? FOR UPDATE", (rs, n) -> rs.getLong(1), location.get("id"));
        long occupied = 0; for (long units : occupiedRows) occupied = safeAdd(occupied, units);
        long next = safeAdd(safeAdd(occupied,UnidentifiedOccupancy.current(jdbc,WarehouseJson.text(location,"id"))), incoming);
        if (maximum > 0 && next > maximum) throw WarehouseException.conflict("Location capacity exceeded / 库位容量不足");
    }

    private void serialAvailable(InventoryMovement request, String excludePool) {
        if (request.serial().isEmpty()) return;
        List<String> matches = jdbc.query("SELECT id FROM wh_inventory WHERE product_id=? AND serial_number=? AND on_hand>0 AND id<>? FOR UPDATE", (rs, n) -> rs.getString(1), request.product(), request.serial(), excludePool == null ? "" : excludePool);
        if (!matches.isEmpty()) throw WarehouseException.conflict("Serial number is already in stock / 此串号已在库");
    }

    private Pool findPool(InventoryMovement request, String warehouse, String location, boolean create, String client) {
        List<Pool> pools = jdbc.query("SELECT * FROM wh_inventory WHERE product_id=? AND warehouse_id=? AND location_id=? AND batch_number=? AND serial_number=? AND expiry_date=? FOR UPDATE", POOL, request.product(), warehouse, location, request.batch(), request.serial(), request.expiry());
        if (!pools.isEmpty()) { Pool pool = pools.get(0); if (!client.equals(pool.client())) throw WarehouseException.conflict("Inventory ownership is inconsistent"); return pool; }
        if (!create) throw WarehouseException.conflict("No matching stock pool / 未找到匹配的库存批次");
        String id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO wh_inventory(id,client_id,product_id,warehouse_id,location_id,batch_number,serial_number,expiry_date,on_hand,reserved,revision) VALUES(?,?,?,?,?,?,?,?,0,0,1)", id, client, request.product(), warehouse, location, request.batch(), request.serial(), request.expiry());
        return readPool(id);
    }

    private Pool readPool(String id) { return jdbc.queryForObject("SELECT * FROM wh_inventory WHERE id=? FOR UPDATE", POOL, id); }

    private List<Layer> assertLayers(Pool pool) {
        List<Layer> layers = jdbc.query("SELECT * FROM wh_stock_layer WHERE inventory_id=? ORDER BY received_at,id FOR UPDATE", (rs, n) -> new Layer(rs.getString("id"), rs.getString("origin_id"), rs.getTimestamp("received_at"), rs.getLong("remaining_quantity"), rs.getLong("reserved_quantity")), pool.id());
        long onhand = 0, reserved = 0;
        for (Layer layer : layers) {
            if (layer.origin() == null || layer.origin().isBlank() || layer.received() == null || layer.remaining() < 0 || layer.reserved() < 0 || layer.reserved() > layer.remaining()) throw WarehouseException.conflict("Stock source evidence is inconsistent");
            onhand = safeAdd(onhand, layer.remaining()); reserved = safeAdd(reserved, layer.reserved());
        }
        if (onhand != pool.onhand() || reserved != pool.reserved() || reserved > onhand) throw WarehouseException.conflict("Inventory and source layers disagree; reconcile before moving / 库存与来源层不一致，请先核对");
        return layers;
    }

    private void writePool(Pool pool, long delta) {
        long next = safeAdd(pool.onhand(), delta);
        if (next < pool.reserved() || jdbc.update("UPDATE wh_inventory SET on_hand=?,revision=revision+1 WHERE id=? AND revision=? AND reserved=?", next, pool.id(), pool.revision(), pool.reserved()) != 1) throw WarehouseException.conflict("Inventory changed or reserved stock would be removed");
        com.ruoyi.warehouse.finance.StorageHistory.inventory(jdbc,pool.id());
    }

    private void addLayer(Pool pool, String id, String origin, Timestamp received, long quantity) {
        jdbc.update("INSERT INTO wh_stock_layer(id,inventory_id,origin_id,received_at,remaining_quantity,reserved_quantity) VALUES(?,?,?,?,?,0)", id, pool.id(), origin, received, quantity);
    }
    private void ledger(String operation, Pool pool, String layer, long delta, InventoryMovement request) { ledger(operation, pool.id(), layer, delta, request); }
    private void ledger(String operation, String pool, String layer, long delta, InventoryMovement request) {
        jdbc.update("INSERT INTO wh_ledger(operation_id,inventory_id,layer_id,quantity_delta,reserved_delta,reference_id,reason,actor_id) VALUES(?,?,?,?,0,?,?,?)", operation, pool, layer, delta, request.requestId(), request.reason(), scope.actor());
    }
    private static Map<String, Object> layerEvidence(String layer, String origin, Timestamp received, long quantity) {
        return Map.of("id", layer, "originId", origin, "receivedAt", received.toInstant().toString(), "quantity", quantity);
    }
    private static long nonnegative(Object value, String name) { return WarehouseJson.empty(value) ? 0 : WarehouseJson.integer(value, name, 0, MAX_TOTAL); }
    private static long safeAdd(long left, long right) { try { long result = Math.addExact(left, right); if (result < 0 || result > MAX_TOTAL) throw new ArithmeticException(); return result; } catch (ArithmeticException error) { throw WarehouseException.conflict("Inventory quantity exceeds its supported range"); } }
    private static boolean enabled(Map<String, Object> row, String key) { return WarehouseJson.enabled(row.get(key)); }
    private static boolean inactive(Map<String, Object> row) { return Boolean.FALSE.equals(row.get("active")) || "N".equals(row.get("active")); }
    private record Layer(String id, String origin, Timestamp received, long remaining, long reserved) { }
    private record Pool(String id, String client, String product, String warehouse, String location, String batch, String serial, String expiry, long onhand, long reserved, long revision) {
        Map<String, Object> view() {
            Map<String, Object> row = new LinkedHashMap<>(); row.put("id", id); row.put("client", client); row.put("product", product); row.put("warehouse", warehouse); row.put("location", location);
            row.put("batch", batch); row.put("serial", serial); row.put("expiry", expiry); row.put("onhand", onhand); row.put("reserved", reserved); row.put("available", onhand - reserved); row.put("_version", revision); return row;
        }
    }
    private static Pool pool(ResultSet rs) throws SQLException { return new Pool(rs.getString("id"), rs.getString("client_id"), rs.getString("product_id"), rs.getString("warehouse_id"), rs.getString("location_id"), rs.getString("batch_number"), rs.getString("serial_number"), rs.getString("expiry_date"), rs.getLong("on_hand"), rs.getLong("reserved"), rs.getLong("revision")); }
    private static final RowMapper<Pool> POOL = (rs, n) -> pool(rs);
}
