package com.ruoyi.warehouse.inbound;
import java.nio.file.*;
import org.springframework.jdbc.core.JdbcTemplate;
public final class CorrectionSchema {
    public static void create(JdbcTemplate jdbc){try{
        String ddl=Files.readString(Path.of("../sql/warehouse_029_receipt_corrections.sql")).replaceAll("(?m)^--.*$","").replaceAll(" CHARACTER SET [A-Za-z0-9_]+","").replaceAll(" COLLATE[= ]+[A-Za-z0-9_]+","").replaceAll(" ENGINE=InnoDB DEFAULT CHARSET=utf8mb4","").replaceAll("LONGTEXT","VARCHAR(4000000)").replaceAll("JSON_VALID\\([a-z_]+\\)","TRUE").replaceAll(",\\s*KEY [a-z_]+\\([^)]*\\)","");
        for(String sql:ddl.split(";"))if(!sql.isBlank())jdbc.execute(sql);
    }catch(java.io.IOException e){throw new IllegalStateException(e);}}
}
