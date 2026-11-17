package com.ruoyi.warehouse.finance;
import java.nio.file.*;
import org.springframework.jdbc.core.JdbcTemplate;
public final class StorageSchema {
    private StorageSchema() { }
    public static void create(JdbcTemplate jdbc){try{
        String ddl=Files.readString(Path.of("../sql/warehouse_015_storage_history.sql")).replaceAll("(?m)^--.*$","").replaceAll(" CHARACTER SET [A-Za-z0-9_]+","").replaceAll(" COLLATE[= ]+[A-Za-z0-9_]+","").replaceAll(" ENGINE=InnoDB DEFAULT CHARSET=utf8mb4","").replaceAll("\\bJSON\\b","VARCHAR(4000000)");
        for(String sql:ddl.split(";"))if(!sql.isBlank())jdbc.execute(sql);
        com.ruoyi.warehouse.inbound.UnidentifiedSchema.createBase(jdbc);
        String review=Files.readString(Path.of("../sql/warehouse_027_storage_identity_review.sql")).replaceAll("(?m)^--.*$","").replaceAll(" CHARACTER SET [A-Za-z0-9_]+","").replaceAll(" COLLATE[= ]+[A-Za-z0-9_]+","").replaceAll(" ENGINE=InnoDB DEFAULT CHARSET=utf8mb4","").replaceAll("LONGTEXT","VARCHAR(4000000)").replaceAll("JSON_VALID\\([a-z_]+\\)","TRUE");
        for(String sql:review.split(";"))if(!sql.isBlank())jdbc.execute(sql);
    }catch(java.io.IOException e){throw new IllegalStateException(e);}}
}
