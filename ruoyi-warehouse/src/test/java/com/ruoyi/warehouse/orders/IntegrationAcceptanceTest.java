package com.ruoyi.warehouse.orders;

import com.ruoyi.warehouse.data.WarehouseRepository;
import com.ruoyi.warehouse.finance.FinancePeriodService;
import com.ruoyi.warehouse.integrations.*;
import com.ruoyi.warehouse.support.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.env.MockEnvironment;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static com.ruoyi.warehouse.orders.OrderServiceTest.map;

class IntegrationAcceptanceTest {
    OrderServiceTest f;
    IntegrationAcceptanceService service;
    Map<String,Object> raw;
    Map<String,Object> physical;
    IntegrationTransport.Response fulfillment;
    Runnable onRead=()->{};
    @BeforeEach void setup() {
        f=new OrderServiceTest();f.database();when(f.scope.actor()).thenReturn(1L);
        f.jdbc.execute("CREATE TABLE sys_user(user_id BIGINT PRIMARY KEY,status VARCHAR(1),del_flag VARCHAR(1))");f.jdbc.update("INSERT INTO sys_user VALUES(1,'0','0')");
        f.jdbc.execute("CREATE TABLE wh_integration_connection(id VARCHAR(64) PRIMARY KEY,client_id VARCHAR(64),warehouse_id VARCHAR(64),channel_id VARCHAR(64),revision BIGINT DEFAULT 1,site_url VARCHAR(512),credentials_cipher VARCHAR(4000),enabled BOOLEAN DEFAULT TRUE)");
        f.jdbc.execute("CREATE TABLE wh_integration_inbox(id VARCHAR(64) PRIMARY KEY,connection_id VARCHAR(64),revision BIGINT DEFAULT 1,document_json VARCHAR(1000000))");
        f.jdbc.execute("CREATE TABLE wh_integration_authority(connection_id VARCHAR(64) PRIMARY KEY,source_key CHAR(64) UNIQUE,source_identity VARCHAR(512),cutover_at TIMESTAMP,fulfillment_mode VARCHAR(32),active BOOLEAN,reason VARCHAR(500),revision BIGINT DEFAULT 1,updated_by BIGINT,updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        f.jdbc.execute("CREATE TABLE wh_integration_mapping(id CHAR(64) PRIMARY KEY,connection_id VARCHAR(64),external_product_id VARCHAR(19),external_variation_id VARCHAR(19),external_sku VARCHAR(191),product_id VARCHAR(64) REFERENCES wh_record(id),active BOOLEAN,revision BIGINT DEFAULT 1,updated_by BIGINT,updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,UNIQUE(connection_id,external_product_id,external_variation_id))");
        f.jdbc.execute("CREATE TABLE wh_integration_order(inbox_id VARCHAR(64) PRIMARY KEY,connection_id VARCHAR(64),source_key CHAR(64),external_id VARCHAR(64),order_id VARCHAR(64) UNIQUE REFERENCES wh_record(id),evidence_json VARCHAR(1000000),actor_id BIGINT,created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,UNIQUE(source_key,external_id))");
        f.record("channel","channels",map("name","WooCommerce","active",true));
        raw=WarehouseJson.map("""
          {"id":123,"number":"000123","status":"processing","date_created_gmt":"2026-11-01T10:00:00","date_modified_gmt":"2026-11-01T12:00:00","date_paid_gmt":"2026-11-01T10:01:00","currency":"GBP","total":"12.50","refunds":[],"meta_data":[],"shipping":{"first_name":"Synthetic","last_name":"Recipient","address_1":"1 Example Street","city":"Example","country":"GB","postcode":"TEST 1"},"billing":{},"line_items":[{"id":31,"sku":"000012","quantity":2,"product_id":1,"variation_id":0,"total":"12.50","meta_data":[]}]}
          """);
        physical=map("id",1,"sku","000012","type","simple","status","publish","virtual",false,"shipping_required",true,"meta_data",List.of());fulfillment=new IntegrationTransport.Response(200,List.of(),Map.of());
        var transport=spy(new IntegrationTransport("shop.example.test","",new MockEnvironment()));
        IntegrationTransport.Reader reader=(path,query)->{onRead.run();if(path.endsWith("/fulfillments"))return fulfillment;if(path.startsWith("/orders/"))return new IntegrationTransport.Response(200,raw,Map.of());return new IntegrationTransport.Response(200,physical,Map.of());};
        doReturn(reader).when(transport).reader(anyString(),anyMap(),any(Instant.class));
        byte[] key=new byte[32];new java.security.SecureRandom().nextBytes(key);var vault=new IntegrationVault(Base64.getEncoder().encodeToString(key));
        f.jdbc.update("INSERT INTO wh_integration_connection(id,client_id,warehouse_id,channel_id,site_url,credentials_cipher) VALUES('connection','client','warehouse','channel','https://shop.example.test',?)",vault.seal("connection",Map.of("consumerKey","synthetic-reader","consumerSecret","synthetic-value")));
        var document=WooCommerceReader.fetch((path,query)->new IntegrationTransport.Response(200,List.of(raw),Map.of("x-wp-total","1","x-wp-totalpages","1")),Map.of("page",1,"since","2026-11-01T00:00:00Z","until","2026-11-02T00:00:00Z")).orders().get(0);
        f.jdbc.update("INSERT INTO wh_integration_inbox VALUES('item','connection',1,?)",WarehouseJson.write(document));
        var records=new WarehouseRepository(f.jdbc,f.scope);var support=new IntegrationIntakeSupport(records,f.scope,transport,vault);var locks=mock(IntegrationJobLock.class);when(locks.acquire(anyString())).thenAnswer(invocation->mock(IntegrationJobLock.Lease.class));
        f.service=new OrderService(records,f.scope,new OrderStock(records,f.scope),new FinancePeriodService(records,f.scope));f.service.setSourceGuard(new IntegrationOrderGuard(support,locks));
        service=new IntegrationAcceptanceService(support,locks,f.service,new DataSourceTransactionManager(f.jdbc.getDataSource()));
        service.authority("connection",authority(0));service.mapping("connection",mapping(0));
    }
    Map<String,Object> authority(long version){return map("requestId",UUID.randomUUID().toString(),"_version",version,"sourceIdentity","https://shop.example.test","cutoverAt","2026-11-01T00:00:00Z","fulfillmentMode","CORE","active",true,"reason","Synthetic exclusive source test","exclusiveSource",true);}
    Map<String,Object> mapping(long version){return map("requestId",UUID.randomUUID().toString(),"_version",version,"productId","1","variationId","0","sku","000012","product","product","active",true);}
    Map<String,Object> input(){return map("requestId",UUID.randomUUID().toString(),"reviewHash",service.review("connection","item").get("reviewHash"));}
    Map<String,Object> accept(Map<String,Object> body){return service.accept("connection","item",body);}

    @Test void acceptedOrderKeepsSourceAndMappingAndReplaysWithoutAnotherProviderRead() {
        var body=input();var result=accept(body);String id=(String)result.get("orderId");var order=f.service.get(id);
        assertEquals("WOOCOMMERCE",order.get("source"));assertEquals("JAVA_WOO_V1",order.get("workflow"));assertEquals("INTEGRATION",order.get("shippingMode"));assertEquals("000123",order.get("sourceReference"));assertEquals("WooCommerce",order.get("channel"));assertEquals(true,order.get("scanRequired"));assertEquals("NEW",order.get("status"));
        onRead=()->{throw new AssertionError("Committed replay must not read the provider");};assertEquals(result,accept(body));assertEquals(id,accept(map("requestId",UUID.randomUUID().toString(),"reviewHash","a".repeat(64))).get("orderId"));
        assertEquals(1,f.scalar("SELECT COUNT(*) FROM wh_record WHERE kind='orders'"));assertEquals(1,f.scalar("SELECT COUNT(*) FROM wh_integration_order"));assertEquals(1,f.scalar("SELECT COUNT(*) FROM wh_audit WHERE action_name='INTEGRATION_ACCEPT'"));assertEquals(0,f.scalar("SELECT COUNT(*) FROM wh_inventory"));
    }
    @Test void mappingChangesInvalidateAnApprovedPreviewAndCrossClientMappingsFail() {
        var body=input();service.mapping("connection",mapping(1));assertEquals("ACCEPTANCE_REVIEW_CHANGED",assertThrows(IntegrationFailure.class,()->accept(body)).code());assertEquals(0,f.scalar("SELECT COUNT(*) FROM wh_record WHERE kind='orders'"));
        f.record("other","products",map("client","other-client","sku","OTHER","active",true));var bad=mapping(2);bad.put("product","other");assertEquals("MAPPING_CLIENT_MISMATCH",assertThrows(IntegrationFailure.class,()->service.mapping("connection",bad)).code());
    }
    @Test void auditFailureRollsBackOrderReferencesLinkAndRequestJournal() {
        var body=input();f.jdbc.execute("ALTER TABLE wh_audit ADD CONSTRAINT reject_accept CHECK(action_name<>'INTEGRATION_ACCEPT')");assertThrows(RuntimeException.class,()->accept(body));
        assertEquals(0,f.scalar("SELECT COUNT(*) FROM wh_record WHERE kind='orders'"));assertEquals(0,f.scalar("SELECT COUNT(*) FROM wh_integration_order"));assertEquals(0,f.scalar("SELECT COUNT(*) FROM wh_operation_request WHERE operation_type='INTEGRATION_ACCEPT'"));
        f.jdbc.execute("ALTER TABLE wh_audit DROP CONSTRAINT reject_accept");assertEquals(true,accept(body).get("accepted"));
    }
    @Test void revokedAccountDuringRemoteReadCannotCommit() {
        var body=input();onRead=()->f.jdbc.update("UPDATE sys_user SET status='1' WHERE user_id=1");assertThrows(WarehouseException.class,()->accept(body));assertEquals(0,f.scalar("SELECT COUNT(*) FROM wh_record WHERE kind='orders'"));
    }
    @Test void sourceChangesStopWarehouseForwardWorkButCancellationCanReleaseReservations() {
        f.receive("product",5,"location","");String id=(String)accept(input()).get("orderId");assertEquals("ALLOCATED",f.action(id,"allocate").get("status"));assertEquals(2,f.scalar("SELECT SUM(reserved) FROM wh_inventory"));
        raw.put("status","cancelled");assertEquals("SOURCE_ORDER_CHANGED",assertThrows(IntegrationFailure.class,()->f.action(id,"pick")).code());assertEquals(2,f.scalar("SELECT SUM(reserved) FROM wh_inventory"));assertEquals("CANCELLED",f.action(id,"cancel").get("status"));assertEquals(0,f.scalar("SELECT SUM(reserved) FROM wh_inventory"));assertEquals(5,f.scalar("SELECT SUM(on_hand) FROM wh_inventory"));
    }
    @Test void importedOrdersCannotBeEditedAsManualOrDespatchedThroughTheManualShortcut() {
        String id=(String)accept(input()).get("orderId");var edit=f.input(2);edit.put("_version",1);assertThrows(WarehouseException.class,()->f.save(id,edit));assertThrows(WarehouseException.class,()->f.action(id,"despatch"));assertEquals(0,f.scalar("SELECT COUNT(*) FROM wh_record WHERE kind='charges'"));
    }
    @Test void authorityCannotMoveCutoverOrTransferIdentityAndDisablingBlocksNewAcceptance() {
        var body=input();var changed=authority(1);changed.put("cutoverAt","2026-11-02T00:00:00Z");assertThrows(WarehouseException.class,()->service.authority("connection",changed));
        var disabled=authority(1);disabled.put("active",false);service.authority("connection",disabled);assertEquals("SOURCE_AUTHORITY_REQUIRED",assertThrows(IntegrationFailure.class,()->accept(body)).code());
    }
    @Test void missingSkuMappingsAndInvalidAddressesStayVisibleInReviewWithoutCreatingOrders() {
        var disabled=mapping(1);disabled.put("active",false);service.mapping("connection",disabled);var review=service.review("connection","item");assertEquals(false,review.get("ready"));assertTrue(((List<?>)review.get("issues")).contains("SKU_MAPPING_MISSING"));
        assertEquals(0,f.scalar("SELECT COUNT(*) FROM wh_record WHERE kind='orders'"));
    }
    @Test void unresolvedRequestCanBeClosedWithoutAllowingALateOriginalToCreateAnOrder() {
        var body=input();var resolved=service.resolve("connection",map("kind","accept","item","item","payload",body));assertEquals(true,resolved.get("requestCancelled"));
        onRead=()->{throw new AssertionError("A cancelled request must not read the source");};assertEquals("ORIGINAL_REQUEST_CANCELLED",assertThrows(IntegrationFailure.class,()->accept(body)).code());assertEquals(true,service.resolve("connection",map("kind","accept","item","item","payload",body)).get("requestCancelled"));assertEquals(0,f.scalar("SELECT COUNT(*) FROM wh_record WHERE kind='orders'"));
    }
    @Test void resolvingAnAlreadyCommittedRequestReturnsTheOriginalOrderWithoutCancellation() {
        var body=input();var result=accept(body);assertEquals(result,service.resolve("connection",map("kind","accept","item","item","payload",body)));assertEquals(1,f.scalar("SELECT COUNT(*) FROM wh_integration_order"));assertEquals(0,f.scalar("SELECT COUNT(*) FROM wh_audit WHERE action_name='INTEGRATION_REQUEST_CANCEL'"));
    }
    @Test void changingClientCurrencyAfterAcceptanceCannotReuseTheOriginalStoreValueAsAnotherCurrency() {
        f.receive("product",5,"location","");String id=(String)accept(input()).get("orderId");f.client.put("currency","EUR");f.update("client",f.client);
        assertEquals("SOURCE_CURRENCY_MISMATCH",assertThrows(IntegrationFailure.class,()->f.action(id,"allocate")).code());assertEquals(0,f.scalar("SELECT SUM(reserved) FROM wh_inventory"));
    }
    @Test void barcodeUnitsStayLocalWhilePickingStillRequiresFreshSourceVerification() {
        f.receive("product",5,"location","");String id=(String)accept(input()).get("orderId");var allocated=f.action(id,"allocate");var allocation=WarehouseJson.maps(allocated.get("allocations")).get(0);
        onRead=()->{throw new AssertionError("Scanning one unit must not query the store");};
        var scanned=f.tx.execute(status->f.service.workAction(id,map("requestId",UUID.randomUUID().toString(),"_version",allocated.get("_version"),"action","scan","allocationId",allocation.get("id"),"barcode","SYNTHETIC")));
        assertEquals(1,((Number)((Map<?,?>)scanned.get("work")).get("scanEventCount")).intValue());
    }
}
