package com.ruoyi.warehouse.billing;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.TemporalAdjusters;
import java.util.Map;

/** UTC calendar boundaries; a weekly period always starts on Monday. */
public final class BillingPeriods {
    private BillingPeriods() { }
    public static Map<String, String> period(String date, String frequency) {
        try {
            if (date == null || !date.matches("\\d{4}-\\d{2}-\\d{2}")) throw new DateTimeParseException("Invalid date", "", 0);
            LocalDate day = LocalDate.parse(date), start = day, end = day;
            if ("Weekly".equals(frequency)) { start = day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)); end = start.plusDays(6); }
            if ("Monthly".equals(frequency)) { start = day.withDayOfMonth(1); end = day.with(TemporalAdjusters.lastDayOfMonth()); }
            return Map.of("from", start.toString(), "to", end.toString());
        } catch (DateTimeParseException error) { throw new IllegalArgumentException("Invalid billing date", error); }
    }
}
