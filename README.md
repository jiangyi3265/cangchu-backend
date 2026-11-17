# cangchu-backend · 海外仓储 Java 后端

若依 Spring Boot 后端，为 cangchu-admin 管理后台提供系统权限与原生 Java 仓储 API。

## 项目简介

保留若依的系统管理、安全认证和动态菜单；ruoyi-warehouse 实现客户与仓库范围隔离、基础资料、ASN 分批收货上架、手工库存、盘点复核、普通订单、隔离退货及计费规则。收货库存层、入库费用、流水、审计和请求去重共同提交；支持按件及每 SKU 收费、柜/托/箱与工时结案费用。后端构建、测试和运行仅使用 Java / Maven，不需要旧 Node 源码或服务。具体范围见 [迁移状态](https://github.com/jiangyi3265/cangchu-warehouse/blob/main/docs/RUOYI_MIGRATION.md)。

WooCommerce 订单读取与仓库接单需依次应用迁移 034 至 037。服务端加密凭据、限制外连地址、检查当前权限，并在整页提交时保留来源版本和审计。单次读取可重放或在中断后恢复；待审资料经来源、商品和履约核验后，才能通过独立接单事务创建仓库订单。创建不占库存，后续分配、拣货和装箱各有校验，当前仍不执行外部写回。环境变量、权限及边界详见 [对接配置](INTEGRATIONS.md)。

DPD UK / DPD Local UK 的加密连接、账号服务查询、受控建单和每箱原始面单已迁入 Java，真实账号与实物交运仍待验收。新版本须先应用全部迁移至 039，默认关闭快递外部请求。启用、权限与恢复见 [CARRIERS.md](CARRIERS.md)。

## 技术栈

Java 17、RuoYi 3.9.2、Spring Boot 3.5.16、Spring Security、MySQL 8、Redis、MyBatis、Spring JDBC、Jackson、JUnit 5。固定上游提交见 [UPSTREAM.md](UPSTREAM.md)。

## 关联仓库

| 项目 | 说明 | GitHub |
| --- | --- | --- |
| cangchu-warehouse | 项目总览、迁移记录与替换验收文档 | [cangchu-warehouse](https://github.com/jiangyi3265/cangchu-warehouse) |
| cangchu-backend | Java / Spring Boot 后端服务 | [cangchu-backend](https://github.com/jiangyi3265/cangchu-backend) |
| cangchu-admin | Vue 3 仓储管理后台 | [cangchu-admin](https://github.com/jiangyi3265/cangchu-admin) |

## 快速启动

在本目录执行构建与测试：

```bash
mvn -B -ntp verify
```

本地开发使用独立 MySQL `127.0.0.1:13306/warehouse_local`、Redis `127.0.0.1:16379`，Java 监听 `127.0.0.1:18080`。提前创建空 schema 和仅有该 schema 权限的非 root 账号。

初始化工具需要 Python 3.10+、Java 17 和 mysql CLI。将 mysql `[client]` 配置放在仓库以外的私有文件中，包含 `host=127.0.0.1`、`port=13306`、`user` 和 `password`。通过环境设置唯一的 16–20 字符 `LOCAL_ADMIN_PASSWORD`，然后执行：

```bash
python tools/initialize-local.py --mysql-config /absolute/private/mysql-client.cnf
```

工具只允许上述回环地址及空 warehouse_local，不重置已有库。它顺序执行若依、Quartz 和 warehouse_*.sql，在执行前替换示例管理员密码并禁用 ry 账号。应用不会自动建表或执行升级 SQL。

已有开发库需按顺序另行执行尚未应用的编号迁移；本次入库功能新增 `warehouse_007_inbound.sql` 和 `warehouse_008_inbound_menu.sql`。迁移创建收货来源记录和菜单，不自动授予员工权限或数据范围。不要对已有库重新运行初始化工具。

订单扫码与装箱另需 `warehouse_009_order_work.sql`、`warehouse_010_order_work_menu.sql`，创建扫码证据、包裹及装箱清单表，增加扫码/撤销/封箱/重新拣货的独立权限。支持客户强制扫码、指定库存串号校验、实测多箱、出库前核验和原请求恢复；DPD 英国面单已有本地协议验证，真实账号、实物交运与包装耗材库存尚未验收。

库存盘点需先应用 `warehouse_011_stocktake.sql`、`warehouse_012_stocktake_menu.sql` 再升级服务；手工库存、ASN 和订单库存写入共同检查盘点冻结。盘点支持实盘记录、补录盘盈、独立复核、差异调整及取消解冻，库存、来源层、流水和审批记录共同提交。管理员自审需显式选择并填写原因。

退货处理需先应用 `warehouse_013_returns.sql`、`warehouse_014_returns_menu.sql`，再升级服务。退货先关联已发订单的实际来源，签收入保管标记的 Quarantine 库位，检验后分别上架或报废；全程保留原批次、串号及来源年龄。收货费按建单时快照的商品/客户首续件价计算，分批接收按累计金额差额入账。新权限须按岗位明确授予。

仓储历史需先应用 `warehouse_015_storage_history.sql` 再升级服务。管理员在财务页启用后，从当前时间记录库存与价格变化；首个完整 UTC 日期之前不能补算。支持七类仓储费、日/周/月完整周期预览和确认入账，使用历史期末价格及库存，峰值库位使用周期内记录。此流程不自动收费，客户合同与历史税额政策仍需另行核对。

费用调整需先应用 `warehouse_016_finance_adjustments.sql`、`warehouse_017_finance_adjustment_menu.sql` 再升级服务。新增批准、执行权限分别授权；默认要求两人操作。原收费依据、草稿重开及确认账单贷项均保留审计，重复执行只入账一次。管理员先配置实际贷项政策和双方主体资料，才能出具非零贷项；配置不会自动填入或执行退款。接口与边界见 [BACKEND-CONTRACT.md](BACKEND-CONTRACT.md)。

还须执行 `warehouse_018_exact_json.sql`：先停止新版业务写入并核验备份，再将仓储业务的 JSON 文档列改为保留原文本的 LONGTEXT，继续由 MySQL CHECK 校验 JSON 格式。避免高精度金额、价格和审计证据经二进制 JSON 转换后丢失小数；最低要求 MySQL 8.0.16。已有被舍入的数值不会自动恢复。服务启动会检查结构和约束，缺少该迁移时拒绝启动。若依系统表不受此迁移影响。

入库待检与上架需再应用 `warehouse_019_asn_custody.sql`、`warehouse_020_asn_custody_menu.sql`。支持预报商品隔离签收、检验、超收批准、分批上架和不合格报废。隔离实物不计可用库存；上架保留来源库龄并与入库费用同事务提交。五项新权限独立授权，未完成的保管任务会阻止直接收货和结案。全损到货处理完后可由管理员明确短收结案。

隔离货退供另需 `warehouse_021_asn_supplier_return.sql`、`warehouse_022_asn_supplier_return_menu.sql`。退供批准、实际交接、撤销未交接数量分别授权；批准锁定收货方、地址和单据，交接需凭据及实物确认，按实际交接数量扣减原隔离来源。剩余货物仍可检验上架；退供不会虚增已收货或生成入库件数费用。

迟到补收需继续应用 `warehouse_023_asn_late_supplement.sql`、`warehouse_024_asn_late_supplement_menu.sql`。已结案原单保留不变，已知商品另建关联补单；独立权限明确批准管理费是否另收、SKU 费是否与原单合并。并发补单不能重复占用短收或重复收取合并 SKU 费。取消未收货补单或短收结案可释放未接受的关联数量。

未识别到货需继续应用 `warehouse_025_unidentified_arrivals.sql`、`warehouse_026_unidentified_arrivals_menu.sql`。迁移前停止写入并验证备份；四项新权限分别授权，不自动授予角色。未知实物按客户、仓库和隔离库位登记，独立容量计数与商品库存共同受库位限制。批准认领后生成待检任务并保留原到仓时间，意外商品以零原预报展示，需单独批准超收。匿名退供/报废记录批准接收方、目的地及实际凭据，不创建虚构商品库存。库存、来源、匿名数量、历史、审计与请求去重共同提交。预约、已结案未知货关联转移、正常库存退供及承运商取件仍待开发。

历史仓储商品归属复核需应用 `warehouse_027_storage_identity_review.sql`、`warehouse_028_storage_identity_review_menu.sql`。保留原始历史，逐件核对未识别数量；历史商品沿用当期计费资料，后来新增商品必须有明确凭据与补充资料。保存、独立批准、周期结算使用版本和请求去重，批准人不能执行这笔结算。新审批权限不自动授予角色。已结算复核不能撤销，后续金额调整走财务调整流程。

收货数量多录更正需继续应用 `warehouse_029_receipt_corrections.sql`、`warehouse_030_receipt_corrections_menu.sql`。迁移前停写并核验备份；批准、执行、撤销三项权限分别授予，批准人不能执行自己的方案。原收货行经审计移库关系追踪到当前来源，只能更正未预留的在库数量；批准后库存变化要求重新预览。库存、ASN 有效实收、审计和财务待办同事务提交；原收货行、原费用及原结案内容保留。财务待办可关联普通调整申请或有据保留收费，不自动退款。错商品、错批次更正尚未实现。

主数据文件导入需应用 `warehouse_031_master_import.sql`、`warehouse_032_master_import_menu.sql`，迁移前停写并核验备份。客户、商品、仓库、库位类型及库位分别增加 import / export 权限，既有新增、修改、查询、财务字段及数据范围权限继续生效。预检通过完整业务保存逻辑演练后回滚，仅保存通过的预检证据；正式提交重新核对版本、关联资料及结果哈希，整批成功或整批回滚。支持 CSV / XLSX、逐行报错、同文件重复检测、请求重试与历史查询；XLSX 导出用文本保存编码和精确金额。每批最多 500 行、上传不超过 2 MiB，导出可按筛选分页下载。此入口不导入库存余额、订单或账单。

供应商和销售渠道另需 `warehouse_033_shared_master_menu.sql`，增加两个菜单和各自的七项操作权限，不自动给已有角色授权。两类资料为共享目录，所有写入及导入仅超级管理员可执行；授权员工的目录读取权限不会放宽单据的客户和仓库范围。ASN 可传 `supplierId`，手工订单可传 `channelId`；修改时省略保留原关联，空字符串明确清除，名称由服务端保存快照。新关联要求启用状态，停用不阻断已有单据作业。非 Manual 的新渠道阶梯规则必须对应已登记的启用渠道，已使用渠道保留名称和引用。当前仍是手工订单流程，不提供平台同步。

在启动 Java 的同一进程环境中设置：

| 变量 | 值或要求 |
| --- | --- |
| SPRING_PROFILES_ACTIVE | local |
| LOCAL_DB_USERNAME、LOCAL_DB_PASSWORD | 独立 MySQL 账号与密码 |
| LOCAL_TOKEN_SECRET | 独立随机密钥，至少 32 随机字节 |
| LOCAL_REDIS_PASSWORD | 本地 Redis 密码；仅隔离本地实例允许留空 |
| WAREHOUSE_LOG_PATH | 仓库外私有日志目录 |
| LOCAL_UPLOAD_PATH | 仓库外上传目录 |

```bash
java -jar ruoyi-admin/target/ruoyi-admin.jar
```

通过前端使用 admin 和初始化密码登录。工具及应用不读取根目录 .env。当前迁移版尚未达到生产替换条件；默认 prod profile 的完整变量见 [BACKEND-CONTRACT.md](BACKEND-CONTRACT.md)，生产密钥没有默认值。

## 项目结构

- ruoyi-admin：可执行入口与若依系统 API。
- ruoyi-framework、ruoyi-common、ruoyi-system：认证、权限与账号。
- ruoyi-warehouse/src/main/java/com/ruoyi/warehouse：原生业务与范围隔离。
- ruoyi-warehouse/src/test：公式对照、权限、边界与 JDBC 事务测试。
- sql：若依建表和按编号执行的仓储迁移。
- tools：空本地库初始化，没有生产连接或重置数据的开关。

## 简历描述示例

开发基于若依 Spring Boot 的海外仓储后端，完成库存来源层、幂等请求、客户与仓库数据范围及订单履约事务。使用 BigDecimal 迁移计费公式，通过旧算法对照和并发事务验证防止重复收费与超扣库存。

## 上游与许可

[RuoYi-Vue 官方仓库](https://github.com/yangzongzhuan/RuoYi-Vue)；本目录保留 [MIT LICENSE](LICENSE)。与上游的差异见 [UPSTREAM.md](UPSTREAM.md)。
