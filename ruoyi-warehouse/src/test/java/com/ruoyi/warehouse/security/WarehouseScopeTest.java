package com.ruoyi.warehouse.security;

import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.warehouse.catalog.WarehouseCatalog;
import com.ruoyi.warehouse.support.WarehouseException;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class WarehouseScopeTest {
    JdbcTemplate jdbc;WarehouseScope scope;
    @BeforeEach void setup(){jdbc=new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1"));jdbc.execute("CREATE TABLE wh_user_scope(user_id BIGINT,scope_type VARCHAR(16),scope_id VARCHAR(64))");scope=new WarehouseScope(jdbc);login(2L,Set.of("warehouse:products:list"));}
    @AfterEach void clear(){SecurityContextHolder.clearContext();}
    void login(long id,Set<String> permissions){var user=new SysUser();user.setUserId(id);user.setUserName("test-user");var principal=new LoginUser(id,1L,user,permissions);SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal,null,List.of()));}
    @Test void sharedCatalogNeedsItsOwnReadPermissionAndNeverGrantsBusinessScope(){
        for(String kind:List.of("suppliers","channels")){
            login(2L,Set.of("warehouse:products:list"));assertThrows(WarehouseException.class,()->scope.assertRecord(kind,Map.of("id","shared","name","Catalog")));
            login(2L,Set.of("warehouse:"+kind+":list"));assertDoesNotThrow(()->scope.assertRecord(kind,Map.of("id","shared","name","Catalog")));assertEquals("1=1",scope.predicate(kind,new ArrayList<>()));
            assertThrows(WarehouseException.class,()->scope.assertRecord("orders",Map.of("client","c1","warehouse","w1")));assertThrows(WarehouseException.class,()->scope.requireAdministrator());
        }
    }
    @Test void absentClientAndWarehouseGrantsDenyAccess(){assertThrows(WarehouseException.class,()->scope.assertRecord("products",Map.of("client","c1")));assertThrows(WarehouseException.class,()->scope.assertRecord("locations",Map.of("warehouse","w1")));}
    @Test void bothClientAndWarehouseAreRequiredForBusinessRows(){jdbc.update("INSERT INTO wh_user_scope VALUES(2,'CLIENT','c1')");assertDoesNotThrow(()->scope.assertRecord("products",Map.of("client","c1")));assertThrows(WarehouseException.class,()->scope.assertRecord("orders",Map.of("client","c1","warehouse","w1")));jdbc.update("INSERT INTO wh_user_scope VALUES(2,'WAREHOUSE','w1')");assertDoesNotThrow(()->scope.assertRecord("orders",Map.of("client","c1","warehouse","w1")));assertThrows(WarehouseException.class,()->scope.assertRecord("orders",Map.of("client","c2","warehouse","w1")));}
    @Test void onlyOfficialSuperAdminIdBypassesScopes(){login(1L,Set.of("*:*:*"));assertDoesNotThrow(()->scope.assertRecord("orders",Map.of("client","c1","warehouse","w1")));login(2L,Set.of("*:*:*"));assertThrows(WarehouseException.class,()->scope.assertRecord("orders",Map.of("client","c1","warehouse","w1")));}
    @Test void productDefaultLocationMustBeInsideTheWarehouseScope(){
        jdbc.execute("CREATE TABLE wh_record(id VARCHAR(64),kind VARCHAR(32),warehouse_id VARCHAR(64))");
        jdbc.update("INSERT INTO wh_record VALUES('l2','locations','w2')");jdbc.update("INSERT INTO wh_user_scope VALUES(2,'CLIENT','c1')");
        assertThrows(WarehouseException.class,()->scope.assertRecord("products",Map.of("client","c1","defaultLocation","l2")));
        jdbc.update("INSERT INTO wh_user_scope VALUES(2,'WAREHOUSE','w2')");assertDoesNotThrow(()->scope.assertRecord("products",Map.of("client","c1","defaultLocation","l2")));
    }
    @Test void warehousePriceCannotExposeAProductOutsideItsDefaultLocationScope(){
        jdbc.execute("CREATE TABLE wh_record(id VARCHAR(64),kind VARCHAR(32),warehouse_id VARCHAR(64),business_json VARCHAR(500))");
        jdbc.update("INSERT INTO wh_record VALUES('l2','locations','w2','{}')");jdbc.update("INSERT INTO wh_record VALUES('p1','products','',?)","{\"client\":\"c1\",\"defaultLocation\":\"l2\"}");
        jdbc.update("INSERT INTO wh_user_scope VALUES(2,'CLIENT','c1')");jdbc.update("INSERT INTO wh_user_scope VALUES(2,'WAREHOUSE','w1')");
        var rule=Map.<String,Object>of("client","c1","warehouse","w1","product","p1");assertThrows(WarehouseException.class,()->scope.assertRecord("warehousePrices",rule));
        jdbc.update("INSERT INTO wh_user_scope VALUES(2,'WAREHOUSE','w2')");assertDoesNotThrow(()->scope.assertRecord("warehousePrices",rule));
        assertThrows(WarehouseException.class,()->scope.assertRecord("warehousePrices",Map.of("client","c1","warehouse","w1","product","missing")));
    }
    @Test void financialFieldsAreSeparateFromWarehouseReadPermissions(){var fields=new WarehouseFields(new WarehouseCatalog());var data=Map.<String,Object>of("id","p1","price",new java.math.BigDecimal("3.5"),"weight",new java.math.BigDecimal("0.4"),"firstPick",2);
        var redacted=fields.readable("products",data);assertFalse(redacted.containsKey("price"));assertFalse(redacted.containsKey("firstPick"));assertEquals(data.get("weight"),redacted.get("weight"));assertThrows(WarehouseException.class,()->fields.writable("products",Map.of("price",0)));
        login(2L,Set.of("warehouse:finance:query"));var exact=fields.readable("products",data);assertEquals("3.5",exact.get("price"));assertEquals("2",exact.get("firstPick"));assertEquals(data.get("weight"),exact.get("weight"));assertThrows(WarehouseException.class,()->fields.writable("products",Map.of("price",0)));
        assertThrows(WarehouseException.class,()->fields.readableKind("invoices"));assertThrows(WarehouseException.class,()->fields.readableKind("charges"));
    }
}
