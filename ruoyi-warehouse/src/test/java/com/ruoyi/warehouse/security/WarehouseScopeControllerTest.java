package com.ruoyi.warehouse.security;

import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.warehouse.controller.WarehouseScopeController;
import com.ruoyi.warehouse.data.WarehouseRepository;
import com.ruoyi.warehouse.support.WarehouseException;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WarehouseScopeControllerTest {
    JdbcTemplate jdbc; WarehouseScopeController controller; TransactionTemplate tx;
    @BeforeEach void setup(){
        var ds=new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1");jdbc=new JdbcTemplate(ds);tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
        jdbc.execute("CREATE TABLE sys_user(user_id BIGINT PRIMARY KEY,del_flag VARCHAR(1))");jdbc.execute("INSERT INTO sys_user VALUES(2,'0')");
        jdbc.execute("CREATE TABLE wh_user_scope(user_id BIGINT,scope_type VARCHAR(16),scope_id VARCHAR(64),PRIMARY KEY(user_id,scope_type,scope_id))");
        var repo=mock(WarehouseRepository.class);when(repo.jdbc()).thenReturn(jdbc);when(repo.get("clients","c1",true)).thenReturn(Map.of("id","c1"));when(repo.get("warehouses","w1",true)).thenReturn(Map.of("id","w1"));
        controller=new WarehouseScopeController(repo,new WarehouseScope(jdbc));
        var user=new SysUser();user.setUserId(1L);var principal=new LoginUser(1L,1L,user,Set.of("*:*:*"));SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal,null,List.of()));
    }
    @AfterEach void clear(){SecurityContextHolder.clearContext();}
    @SuppressWarnings("unchecked") Map<String,Object> read(){return (Map<String,Object>)controller.get(2).get("data");}
    @Test void staleScopeReplacementCannotOverwriteAnotherSave(){
        var first=read();String version=(String)first.get("_version");assertEquals(64,version.length());
        tx.execute(status->controller.save(2,Map.of("clients",List.of("c1"),"warehouses",List.of("w1"),"_version",version)));
        assertNotEquals(version,read().get("_version"));
        assertThrows(WarehouseException.class,()->tx.execute(status->controller.save(2,Map.of("clients",List.of(),"warehouses",List.of(),"_version",version))));
        assertEquals(List.of("c1"),read().get("clients"));assertEquals(List.of("w1"),read().get("warehouses"));
        tx.execute(status->controller.save(2,Map.of("clients",List.of(),"warehouses",List.of(),"_version",read().get("_version"))));
        assertEquals(List.of(),read().get("clients"));
    }
    @Test void aVersionIsRequiredAndSuperAdministratorScopeIsNotEditable(){
        assertThrows(WarehouseException.class,()->tx.execute(status->controller.save(2,Map.of("clients",List.of(),"warehouses",List.of()))));
        assertThrows(WarehouseException.class,()->tx.execute(status->controller.save(1,Map.of("clients",List.of(),"warehouses",List.of(),"_version",read().get("_version")))));
    }
}
