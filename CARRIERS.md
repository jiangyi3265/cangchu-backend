# DPD UK 快递连接与面单

Java 当前实现 DPD UK / DPD Local UK Customer API 的英国境内服务查询、受控建单和原始 ZPL 面单获取。账号必须与所选业务一致，两者不能混用。这是已装箱订单的面单流程；实物交运、库存扣减、发货计费和平台回传尚未接通，不能据此进行正式替换。

## 启用前

先停写并验证备份，按顺序应用全部仓储迁移至 `warehouse_039_carriers_menu.sql`。038 新增加密连接、不可变发运快照、每箱原始面单和已关闭请求记录；039 新增快递菜单及独立权限。迁移不自动授予员工权限，启动检查要求完整结构及约束。

| 服务器环境变量 | 要求 |
| --- | --- |
| `WAREHOUSE_CARRIERS_ENCRYPTION_KEY` | 独立的 32 随机字节、Base64 编码，存于服务器私有环境；不复用店铺加密上下文 |
| `WAREHOUSE_CARRIERS_ENABLED` | 默认 `false`；真实账号验收及受控切换前保持关闭 |

超级管理员在“快递发运 → 快递连接”设置客户、仓库、环境、DPD 业务、英国发件地址和打印机 DPI（203 / 300）。凭据为当前 Customer API 的 App key / App secret，旧 GeoSession、Mintsoft 登录账号不能直接代替。凭据 AES-256-GCM 加密后保存；接口、审计及浏览器恢复记录不返回凭据。空凭据编辑保留原值，改密钥须同时填入原 App key 与新 App secret；更换账号、业务、环境或客户/仓库须新增连接。连接停用不解除既有快递任务对订单的保护。

## 仓库操作

1. 普通 Java 手工订单或已接入的 WooCommerce 来源订单，先完成分配、逐件扫码、确认拣货和全部封箱。真实包裹必须有实测重量与尺寸。
2. 从订单详情选择归属一致的启用连接，查询当前账号返回的可用服务。DPD Local 与 DPD 通过授权中的业务标识复核；不硬编码可用服务编号。
3. 选择英国当地今天至七天内的发运日期。服务要求通知时须有收件人邮箱和通知手机号。再明确确认申请面单。
4. 每个包裹独立关联原运单号和原始 ZPL；可从订单详情或快递记录下载。当前不自动发送到打印机，也不将面单取得视为实物交运。

当前只接受 `GB` 普通境内地址，拒绝需要单独处理的 BT/GY/JE/IM/BF、邮政信箱、地址第三行及超长字段，不静默截断。拒绝已配置特殊运输资料的商品；不猜测报关资料。服务要求未支持的必填资料时显示不可选。每单 1–99 个包裹；总重按精确十进制计算，接口总重范围 0.1–99999 kg。此范围不是具体服务的承运重量承诺，实际限制仍需账号级验收。此 domestic API 未定义单箱尺寸字段，尺寸留存在仓库包裹快照中，不虚构发送字段。

## 状态与恢复

| 状态 | 处理 |
| --- | --- |
| PREPARING | 本地已留存申请，远程创建尚未开始；可重新核验后继续 |
| SENDING | 已提交创建意图；进程中断后转 UNKNOWN，不再次创建 |
| CREATED | 已保存完整远程标识；只查询缺失的原面单 |
| LABELLED | 每箱原始面单均已保存，订单仍保持已拣货 |
| REJECTED | 发出前失败，或 DPD 明确拒绝；原请求重放保持原结果，可另发新申请 |
| UNKNOWN | 创建结果无法确定；保留参考号供 DPD 核查，不解锁仓库作业、不自动重下 |

订单行锁和跨进程任务锁串行保护申请；写入发送意图及审计后才允许创建。创建成功后先保存远程标识，再查询面单。HTTP 自动重试、跳转及 Cookie 关闭；45 秒操作预算、8 秒网络阶段限制，单轮最多补取 10 张，已取得的面单不重复获取。单次响应最多 4 MiB，面单合计最多 2 MiB，并校验原文 SHA-256。

浏览器响应丢失时使用“核对原申请”。若已有记录，返回原记录；若尚未接收，则持久关闭该原请求号，阻止其稍后到达重新建单。配置保存也使用此机制，恢复记录不含密钥。PREPARING / CREATED 可按既有状态继续；UNKNOWN 没有一键重新创建或伪造取消入口。申请存在时，普通编辑、取消、拆箱和人工发货均受后端保护。

操作权限及客户/仓库范围会在远程调用前重新检查。若发送途中撤销权限，仍保留已收到的远程标识，随后停止后续请求并拒绝向该员工返回资料。凭据轮换及停用不能把同一申请改绑其他账号。

HTTP 400 且全部错误为 101/102/103，以及 401/403/429，按明确拒绝处理；500、系统错误、超时、无法解析或不完整创建标识按结果不明处理。当前没有自动重试计划或持久 Retry-After 调度，限流后需稍后手工处理；账号级限流与定时任务仍待补齐。

## 接口与权限

基础路径 `/warehouse/carriers`，沿用若依 JWT：

| 路径 | 方法 / 权限 | 用途 |
| --- | --- | --- |
| `/capabilities`、`/connections`、`/shipments` | GET / list | 功能开关、范围内连接与分页发运记录 |
| `/connections/{id}`、`/shipments/{id}`、`/orders/{id}` | GET / query | 连接、单次发运、订单申请记录 |
| `/connections`、`/connections/{id}` | POST / PUT / configure | 仅超级管理员配置，带 requestId、更新带版本 |
| `/orders/{id}/services` | POST / services | 账号服务查询，无远程业务写入 |
| `/orders/{id}/book` | POST / book | 固定请求号、订单版本和连接版本申请 |
| `/shipments/{id}/recover` | POST / recover | 按原状态继续或补取原面单 |
| `/shipments/{id}/labels/{index}` | GET / label | 原始 ZPL 附件，禁止缓存 |
| `/orders/{id}/resolve` | POST / book | 核对已有申请或持久关闭未落库请求 |
| `/connections/{id}/resolve` | POST / configure | 核对配置保存或持久关闭延迟保存请求 |

权限前缀统一为 `warehouse:carriers:`。连接列表最多 200 条；订单申请历史最多 100 条；发运列表每页最多 100 条。

## 本地验证与剩余边界

只有显式 `warehouse-carrier-test` profile、Java 绑定 `127.0.0.1` 并配置 `WAREHOUSE_CARRIERS_LOOPBACK_TEST_ORIGIN=http://127.0.0.1:端口` 时才允许回环协议测试。页面会明确显示模拟模式。普通环境固定使用 DPD 官方测试/正式地址，并拒绝解析到非公网地址；无任意外部地址输入入口。

本轮证据包含单元/事务测试、真实本地 MySQL / JWT、跨请求并发、两箱原始面单、创建返回 500、权限途中撤销、浏览器丢响应恢复和真实 Java 进程在发送后中断的验证。模拟数据没有提交 DPD，不能算真实账号联调或真实面单验收。

未实现：实物交运及原生费用/库存扣减、物流追踪、清单/取件、取消/作废、结果不明的人工对账结案、库存/发货平台回传、真实账号与打印机验收、其他国家/特殊地址/危险品/报关及其他快递适配。公开的此版 Shipping API 未提供运单作废接口，取件取消不能替代运单取消；Tracking API 返回资料不能可靠提供补取标签所需的 shipmentId，因此不靠猜测恢复未知建单。全部剩余要求保留在[完整替换验收表](https://github.com/jiangyi3265/cangchu-warehouse/blob/main/docs/REPLACEMENT_ACCEPTANCE.md)。

## 协议依据

2026-11-16 读取 DPD 官方 OpenAPI 1.0.0：[授权](https://developers.api.dpd.co.uk/docs/apigee-customer-shipping-apis/1.0.0/schema.json)、[发运](https://developers.api.dpd.co.uk/docs/customer-shipping-apis/1.0.0/schema.json)、[追踪](https://developers.api.dpd.co.uk/docs/apigee-customer-tracking-apis/1.0.0/schema.json)。协议快照 SHA-256 分别为 `328d13eaab2a5db411a52841b95f7f7b1fe694d9ac34e416606fb9427c529f82`、`c7d7dcc5a1910f743c164b9753d8a6c70e3b018399d6eafabf774c8bf17764d0`、`b030bdbd68c669c2f1d5a2ab99805826a99ad5e710f560791a610bc758d0848e`。快照只用于协议复核，不是客户账号授权。
