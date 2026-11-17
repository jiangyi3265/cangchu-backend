package com.ruoyi.warehouse.finance;

import com.ruoyi.warehouse.billing.BillingPeriods;
import com.ruoyi.warehouse.data.WarehouseRepository;
import com.ruoyi.warehouse.security.WarehouseScope;
import com.ruoyi.warehouse.support.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.time.*;
import java.util.*;

/** Internal despatch hook. Snapshot and membership commit with stock and order. */
@Service
public class FinancePeriodService {
    private final WarehouseRepository records;private final WarehouseScope scope;private final JdbcTemplate jdbc;
    public FinancePeriodService(WarehouseRepository records,WarehouseScope scope){this.records=records;this.scope=scope;this.jdbc=records.jdbc();}
    public String registerOrder(Map<String,Object> order,Map<String,Object> client,Map<String,Object> rule){
        if(!TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Period registration requires the despatch transaction");
        scope.assertRecord("orders",order);String orderId=WarehouseJson.text(order,"id"),ruleId=WarehouseJson.text(rule,"id"),clientId=WarehouseJson.text(order,"client");
        if(orderId.isEmpty()||ruleId.isEmpty()||!clientId.equals(client.get("id"))||!clientId.equals(rule.get("client"))||!"DESPATCHED".equals(order.get("status"))||!"Orders".equals(rule.getOrDefault("basis","Orders")))throw WarehouseException.invalid("Invalid period registration");
        String frequency=WarehouseJson.empty(client.get("tierFrequency"))?"Daily":FinanceInput.option(client,"tierFrequency","Daily","Daily","Weekly","Monthly");String date;
        try{date=Instant.parse(WarehouseJson.text(order,"despatchedAt")).atOffset(ZoneOffset.UTC).toLocalDate().toString();}catch(Exception e){throw WarehouseException.invalid("Invalid despatch timestamp");}
        if(!date.equals(LocalDate.now(ZoneOffset.UTC).toString()))throw WarehouseException.conflict("Historical order registration needs a reviewed migration workflow");
        var range=BillingPeriods.period(date,frequency);String period=FinanceInput.hash(List.of(ruleId,range.get("from"),range.get("to")));
        records.get("clients",clientId,true);
        var overlaps=jdbc.queryForList("SELECT id FROM wh_finance_period WHERE rule_id=? AND from_date<=? AND to_date>=? ORDER BY id FOR UPDATE",ruleId,range.get("to"),range.get("from"));
        if(overlaps.stream().anyMatch(p->!period.equals(String.valueOf(p.get("id")))))throw WarehouseException.conflict("Tier frequency changed inside an existing period; use an explicit new rule after the period");
        String currency=WarehouseJson.empty(client.get("currency"))?"GBP":FinanceInput.option(client,"currency","GBP","GBP","USD","EUR","CNY");
        jdbc.update("INSERT INTO wh_finance_period(id,rule_id,client_id,from_date,to_date,currency,snapshot_json) VALUES(?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE id=id",period,ruleId,clientId,range.get("from"),range.get("to"),currency,WarehouseJson.write(Map.of("version",1,"frequency",frequency,"rule",rule)));
        var current=jdbc.queryForMap("SELECT client_id,currency,settled_count FROM wh_finance_period WHERE id=? FOR UPDATE",period);
        if(!clientId.equals(current.get("client_id"))||!currency.equals(current.get("currency"))||((Number)current.get("settled_count")).longValue()!=0)throw WarehouseException.conflict("Period already settled or currency changed");
        jdbc.update("INSERT INTO wh_finance_tier_order(order_id,period_id,warehouse_id) VALUES(?,?,?)",orderId,period,order.get("warehouse"));
        return period;
    }
}
