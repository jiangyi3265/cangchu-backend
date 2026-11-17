package com.ruoyi.warehouse.inventory;

import com.ruoyi.warehouse.support.WarehouseException;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

/** Location-parent lock is required; a separate counter avoids locking ASN rows in reverse order. */
public final class UnidentifiedOccupancy {
    private UnidentifiedOccupancy() { }
    public static long current(JdbcTemplate jdbc,String location){
        List<Long> rows=jdbc.queryForList("SELECT quantity FROM wh_unidentified_occupancy WHERE location_id=? FOR UPDATE",Long.class,location);return rows.isEmpty()?0:rows.get(0);
    }
    public static void change(JdbcTemplate jdbc,String location,long delta){
        long next=Math.addExact(current(jdbc,location),delta);if(next<0)throw WarehouseException.conflict("Unidentified physical quantity does not reconcile / 未识别实物数量不一致");
        jdbc.update("INSERT INTO wh_unidentified_occupancy(location_id,quantity) VALUES(?,?) ON DUPLICATE KEY UPDATE quantity=?",location,next,next);
    }
}
