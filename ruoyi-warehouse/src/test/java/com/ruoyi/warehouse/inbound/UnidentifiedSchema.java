package com.ruoyi.warehouse.inbound;
import java.nio.file.*;
import org.springframework.jdbc.core.JdbcTemplate;
public final class UnidentifiedSchema {
    private UnidentifiedSchema() { }
    public static void createBase(JdbcTemplate jdbc){create(jdbc,true);}
    public static void createActions(JdbcTemplate jdbc){create(jdbc,false);}
    private static void create(JdbcTemplate jdbc,boolean base){try{
        String ddl=Files.readString(Path.of("../sql/warehouse_025_unidentified_arrivals.sql")).replaceAll("(?m)^--.*$","").replaceAll(" CHARACTER SET [A-Za-z0-9_]+","").replaceAll(" COLLATE[= ]+[A-Za-z0-9_]+","").replaceAll(" ENGINE=InnoDB DEFAULT CHARSET=utf8mb4","").replaceAll("\\bLONGTEXT\\b","VARCHAR(4000000)").replaceAll("JSON_VALID\\([a-z_]+\\)","TRUE");
        for(String sql:ddl.split(";")){String statement=sql.strip();if(statement.isEmpty())continue;boolean isBase=statement.startsWith("CREATE TABLE wh_unidentified_occupancy")||statement.startsWith("CREATE TABLE wh_asn_unidentified (");if(isBase==base)jdbc.execute(statement);}
    }catch(java.io.IOException e){throw new IllegalStateException(e);}}
}
