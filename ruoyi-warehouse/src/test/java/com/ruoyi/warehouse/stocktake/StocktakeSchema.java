package com.ruoyi.warehouse.stocktake;
import java.nio.file.*;
import org.springframework.jdbc.core.JdbcTemplate;

public final class StocktakeSchema {
    private StocktakeSchema() { }
    public static void create(JdbcTemplate jdbc) {
        try {
            String ddl=Files.readString(Path.of("../sql/warehouse_011_stocktake.sql")).replaceAll("(?m)^--.*$","").replaceAll(" CHARACTER SET [A-Za-z0-9_]+","").replaceAll(" COLLATE[= ]+[A-Za-z0-9_]+","").replaceAll(" ENGINE=InnoDB DEFAULT CHARSET=utf8mb4","").replaceAll("\\bJSON\\b","VARCHAR(1000000)");
            for(String statement:ddl.split(";"))if(!statement.isBlank())jdbc.execute(statement);
        }catch(java.io.IOException e){throw new IllegalStateException(e);}
    }
}
