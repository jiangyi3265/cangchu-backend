package com.ruoyi.warehouse.integrations;

import com.ruoyi.warehouse.support.WarehouseException;
import java.sql.*;
import javax.sql.DataSource;
import org.springframework.stereotype.Component;

/** A live database-session lock distinguishes an in-flight pull from a crashed request. */
@Component
public class IntegrationJobLock {
    private final DataSource source;
    public IntegrationJobLock(DataSource source){this.source=source;}
    static String name(String id){return "wh-pull:"+IntegrationService.hash(id).substring(0,48);}
    public Lease acquire(String id){Connection c=null;try{c=source.getConnection();try(var s=c.prepareStatement("SELECT GET_LOCK(?,0)")){s.setString(1,name(id));try(var r=s.executeQuery()){if(!r.next()||r.getInt(1)!=1)throw WarehouseException.conflict("A pull is already running / 此连接正在拉取，请稍后查看结果");}}return new Lease(c,name(id));}catch(Exception e){if(c!=null)try{c.close();}catch(Exception ignored){}if(e instanceof WarehouseException w)throw w;throw new IntegrationFailure("JOB_LOCK_UNAVAILABLE");}}
    public static final class Lease implements AutoCloseable {
        private final Connection connection;private final String name;
        private Lease(Connection c,String name){connection=c;this.name=name;}
        @Override public void close(){try(var s=connection.prepareStatement("SELECT RELEASE_LOCK(?)")){s.setString(1,name);try(var r=s.executeQuery()){if(!r.next()||r.getInt(1)!=1)throw new SQLException();}}catch(Exception e){try{connection.abort(Runnable::run);}catch(Exception ignored){}}finally{try{connection.close();}catch(Exception ignored){}}}
    }
}
