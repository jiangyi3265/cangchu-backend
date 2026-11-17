package com.ruoyi.warehouse.orders;

import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.jdbc.core.JdbcTemplate;

public final class OrderWorkSchema {
    private OrderWorkSchema() { }
    public static void create(JdbcTemplate jdbc) {
        try {
            String ddl=Files.readString(Path.of("../sql/warehouse_009_order_work.sql")).replaceAll("(?m)^--.*$","").replaceAll(" CHARACTER SET [A-Za-z0-9_]+","").replaceAll(" COLLATE[= ]+[A-Za-z0-9_]+","").replaceAll(" ENGINE=InnoDB DEFAULT CHARSET=utf8mb4","");
            for(String statement:ddl.split(";"))if(!statement.isBlank())jdbc.execute(statement);
        } catch(java.io.IOException error) { throw new IllegalStateException(error); }
    }
}
