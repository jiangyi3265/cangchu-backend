package com.ruoyi.warehouse.inventory;

import com.ruoyi.warehouse.support.WarehouseException;
import org.springframework.jdbc.core.JdbcTemplate;

/** Every stock writer calls this after acquiring the location parent lock. */
public final class StocktakeLock {
    private StocktakeLock() { }
    public static String owner(JdbcTemplate jdbc, String client, String warehouse, String location) {
        var owners = jdbc.queryForList("SELECT stocktake_id FROM wh_stocktake_lock WHERE client_id=? AND warehouse_id=? AND location_id=? FOR UPDATE", String.class, client, warehouse, location);
        return owners.isEmpty() ? "" : owners.get(0);
    }
    public static void available(JdbcTemplate jdbc, String client, String warehouse, String location) {
        if (!owner(jdbc, client, warehouse, location).isEmpty()) throw WarehouseException.conflict("Stock is frozen for physical counting / 此客户库位正在盘点，请先完成或取消盘点");
    }
}
