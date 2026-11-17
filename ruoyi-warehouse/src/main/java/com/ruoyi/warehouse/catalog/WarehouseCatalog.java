package com.ruoyi.warehouse.catalog;

import com.ruoyi.warehouse.support.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Component
public class WarehouseCatalog {
    private final Map<String,Map<String,Object>> entities=new LinkedHashMap<>();
    public WarehouseCatalog(){try(var in=new ClassPathResource("warehouse/catalog.json").getInputStream()){
        for(var item:WarehouseJson.maps(WarehouseJson.map(new String(in.readAllBytes(),StandardCharsets.UTF_8)).get("entities")))entities.put(WarehouseJson.text(item,"kind"),item);
    }catch(Exception e){throw new IllegalStateException("Warehouse catalog cannot load",e);}}
    public Map<String,Object> entity(String kind){var value=entities.get(kind);if(value==null)throw WarehouseException.missing();return WarehouseJson.copy(value);}
    public List<Map<String,Object>> fields(String kind){return WarehouseJson.maps(entity(kind).get("fields"));}
    public void editable(String kind){if(!Boolean.FALSE.equals(entity(kind).get("readOnly")))throw new WarehouseException(409,"warehouse_workflow_required","Use a supported warehouse workflow / 请使用已迁移的业务流程");}
    public Collection<Map<String,Object>> all(){return entities.values().stream().map(WarehouseJson::copy).toList();}
    public Map<String,Object> capabilities(){return Map.of("runtime","java-spring-boot","nodeRuntimeRequired",false,"masterData",Map.of("status","implemented","kinds",entities.values().stream().filter(x->Boolean.FALSE.equals(x.get("readOnly"))).map(x->x.get("kind")).toList()),"modules",List.of(
        module("masterData","基础资料","Master data",true,"客户、商品、仓库、库位类型、库位、供应商及渠道支持维护和文件导入导出；供应商可关联入库单，渠道可关联手工订单及收费。","Master records, including shared suppliers and sales channels, support validation, file imports and scoped exports. Suppliers link to ASNs and channels to manual orders and pricing."),
        module("inventory","库存作业","Inventory",true,"手工增减、移库、批次串号、来源层与库存流水已开放；隔离与补货仍待迁移。","Manual adjustments, transfers, tracking, source layers and ledger are available; quarantine and replenishment remain pending."),
        module("stocktakes","库存盘点","Stocktakes",true,"支持库位冻结、实盘、差异复核、盘盈盘亏入账和取消解冻；保管与隔离库存盘点待迁移。","Location freezing, physical counts, difference review, stock reconciliation and cancellation are available; custody and quarantine counts remain pending."),
        module("inbound","入库收货","Inbound receiving",true,"支持预报、分批收货、未识别实物登记与批准认领、隔离检验和处置、退供交接、结案及迟到补单。收货多录可按原来源更正并转财务复核；预约及错商品、错批次更正仍待完成。","Forecasts, partial receipts, unidentified custody, inspection, disposition, supplier handovers, closure and late supplements are available. Excess receipt quantities can be corrected against original sources and queued for financial review; appointments and product or lot relabelling remain pending."),
        module("orders","订单履约","Order fulfillment",true,"普通订单支持分配、逐件扫码、串号核验、多箱装箱、取消和人工出库；组合商品、波次与平台快递仍待迁移。","Ordinary orders support allocation, unit and serial scanning, multi-package packing, cancellation and manual despatch; bundles, waves and integrations remain pending."),
        module("returns","退货处理","Returns",true,"支持原发货关联、隔离签收、检验、分批上架或报废、来源年龄保留及首续件收费；历史无来源退货待迁移。","Original despatch returns support quarantine receipt, inspection, partial restocking or scrapping, original stock age and handling charges; legacy returns without source evidence remain pending."),
        module("billing","计费与账单","Billing",true,"支持订单数阶梯、周期费用、历史仓储计费与商品归属复核、开票、调账审批及贷项单；历史记录需先启用，贷项需配置主体资料；外发与自动结算待迁移。","Order-count tiers, recurring charges, historical storage billing and identity review, invoicing, approved adjustments and credit notes are available. Storage recording and credit profiles require setup; delivery and automatic settlement remain pending."),
        module("integrations","平台与快递","Integrations",false,"外部接口尚未在 Java 版本启用。","External services are not enabled in this Java version."),
        module("migration","历史数据迁移","Legacy migration",false,"旧数据库未导入，不会自动覆盖原系统。","The legacy database is not imported automatically.")) );}
    private Map<String,Object> module(String id,String label,String en,boolean available,String description,String descriptionEn){return Map.of("id",id,"label",label,"labelEn",en,"available",available,"description",description,"descriptionEn",descriptionEn);}
}
