package com.ruoyi.warehouse.support;

import java.time.Duration;
import java.time.Instant;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Fail before business use if JDBC or the database session interprets UTC incorrectly. */
@Component
public class WarehouseDatabaseClock implements ApplicationRunner {
    private final JdbcTemplate jdbc;
    public WarehouseDatabaseClock(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public void run(ApplicationArguments args) {
        jdbc.query("SELECT CURRENT_TIMESTAMP(6), UTC_TIMESTAMP(6)", result -> {
            Instant session = result.getTimestamp(1).toInstant();
            Instant utc = result.getTimestamp(2).toInstant();
            if (!session.equals(utc) || Duration.between(utc, Instant.now()).abs().compareTo(Duration.ofSeconds(30)) > 0) {
                throw new IllegalStateException("Warehouse timestamps require a UTC database session, JDBC connectionTimeZone=UTC and synchronized clocks");
            }
        });
    }
}
