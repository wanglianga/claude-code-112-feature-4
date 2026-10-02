# 城市干洗 · 取洗档案与异常报价决策系统

## 本阶段需求（取洗档案 / 异常证据 / 规则报价 / 衣物级状态机）

订单进入取件环节后，系统把客户下单信息、衣物照片、材质/品牌、污渍描述、服务项、预估价格、
骑手取件记录、门店质检与支付状态关联为**同一份可审计取洗档案**。骑手或门店发现
**材质与订单不符、高风险污渍、配件缺失、无法去除的瑕疵**时，不能直接加价或继续洗护：
必须创建异常项并附**原始照片、拍摄时间、位置、处理建议**，系统按规则生成
「维持原价 / 追加报价 / 拒绝承接 / 仅基础处理」4 个候选方案。客户可在时限内
**确认、拒绝（原路退回 或 按原服务继续）、申请人工复核**；确认追加后才允许排产，
不同选择同步影响**骑手回收、门店产能、支付预授权、预计交付时间**。

对同一衣物**多次补拍、骑手与门店判断不一致、客户确认后撤回、衣物已进入洗护但支付回调失败**
等情形，系统以**衣物级状态机**处理，保留每次报价版本与证据，不覆盖原判断；
退款、补偿、重派、洗护工序均**幂等**，避免重复扣款或重复出库。关单前校验
**证据、客户确认、质检结论、包装/取回记录**是否齐全，缺项时**阻止自动结算并转入人工队列**。
最终页面统一展示衣物明细、异常证据、报价决策、客户反馈、履约状态、支付/退款与责任归属，
方便客服、门店和客户核对同一事实。

---

## 技术栈

- Java 17 + Spring Boot 3.3（Web / Data JPA / Actuator）
- PostgreSQL 16（生产/交付）；H2（仅本地无 Docker 自测 profile）
- Maven 多阶段 Docker 构建，运行镜像基于 `eclipse-temurin:17-jre`，**非 root + HEALTHCHECK**
- 前端：零构建的单页 `src/main/resources/static/dossier.html`（登录后查看/操作取洗档案）
- 认证：登录发 Token，后续请求携带 `X-Auth-Token`（演示用，无外部依赖）

## 一键启动（Docker Compose，交付推荐）

```bash
cp .env.example .env          # 可改 CC_PUBLISH_PORT（默认宿主 3112）
docker compose up -d --build  # 启动 app + PostgreSQL
docker compose ps             # 查看健康状态
```

- 仅发布应用端口：`${CC_PUBLISH_PORT}:8080`（默认 3112）；PostgreSQL 不发布宿主端口。
- 健康检查：`GET /actuator/health` 返回 `{"status":"UP"}`。
- 访问档案页：`http://localhost:3112/dossier.html`（顶部选角色登录，输入订单 ID 查询）。
- 停止：`docker compose down`（保留数据）；彻底清理：`docker compose down -v`。

## 本地开发与自测（无需 Docker / PostgreSQL）

```bash
mvn spring-boot:run -Ph2          # 使用内存 H2，数据重启即清空，默认端口 8080
# 浏览器打开 http://localhost:8080/dossier.html
```

打包（默认 PostgreSQL 配置）：

```bash
mvn clean package -DskipTests    # 产出 target/city-laundry-1.0.0.jar
```

## 演示账号（用户名 / 密码，均为 pass123）

| 角色 | 用户名 | 在本流程中的权限 |
|---|---|---|
| 客户 | `customer1` / `customer2` | 下单；对报价**确认/拒绝(退回或继续)/人工复核/确认后撤回**；查看自己的档案 |
| 骑手 | `picker1` | 确认取件（建档+预授权）、送达门店、多次补拍、完成回收交接 |
| 门店经理 | `manager1` | 收件质检、建异常、补拍/补充研判、产能查看、高价值复核、关单 |
| 工厂 | `factory1` | 入厂、分配工艺、洗护复拍、在途异常上报 |
| 客服 | `cs1` | 受理人工复核（维持/下调/维持原价/仅基础/拒单）、关单、补齐缺项 |
| 配送 | `courier1` | 交付（请款+售后记录） |
| 财务 | `finance1` | 支付回调、失败重试、赔付审批/执行 |

基础数据：2 家门店（1 号店接收高风险/日限 5 件；2 号店不接收高风险）、3 个工厂站点。

## 核心业务流与关键规则

```
下单(全量衣物信息+估价)
 → 骑手拍照齐 → 骑手确认取件(取洗档案建立：时间/位置/签字 + 按下单估价预授权冻结)
 → 骑手送达门店 → 门店逐件收件质检
     ├─ 一致：质检通过放行
     └─ 材质不符/高风险污渍/配件缺失/不可去除瑕疵
           → 建异常项(原始照片+拍摄时间+位置+建议，禁止直接加价/洗护)
           → 规则自动生成4候选报价版本(维持原价/追加报价/拒绝承接/仅基础处理)
           → 客户时限内：
               · 确认追加 → 补充预授权差额、占门店产能、允许排产(可在排产前撤回重出版本)
               · 拒绝→原路退回：释放预授权 + 骑手幂等回收
               · 拒绝→按原服务/仅基础处理继续
               · 申请人工复核：客服维持/下调(必出新版报价)/维持原价/仅基础/拒单
           → 多次补拍 / 骑手门店判断不一致：证据与双方研判均保留，旧报价 SUPERSEDED 不覆盖
 → 入厂洗护（按确认后实际材质定工艺；请款渠道回调失败可在洗护中安全重试，幂等不重复扣款）
 → 复拍 + 包装确认 → 交付（按账单请款、释放剩余预授权、逐件售后）
 → 关单校验(证据/客户确认/质检/包装/取回)：缺项阻断自动结算→转人工队列；齐全→结算关单封存
```

关键设计：

- **衣物级状态机**（`GarmentStateMachine`）：每件衣物独立流转，异常只挂单件不拖垮整单；
  非法迁移直接 409 拒绝，杜绝跳步/覆盖。
- **报价版本不覆盖**：补拍、分歧、撤回、人工复核都生成新版本，历史版本置 `SUPERSEDED` 永久保留。
- **规则候选**：高风险材质/高风险污渍默认不可「维持原价」；永久瑕疵不可追加报价；
  材质升级按实际工艺差价计算追加额，规则命中过程写入 `ruleTrace`。
- **资金幂等**：支付回调 `idempotencyKey` 唯一落库 + 会员卡流水幂等键双保险；
  预授权=冻结，确认追加补冻结差额，拒绝/关单释放，退款不超过已请款。
- **履约幂等**：骑手回收按「异常+原因」幂等复用任务；交付/关单/结算重复调用返回原结果。
- **责任归属**：按异常类别、双方证据与已执行赔付逐件推导，档案与时间线可审计。

## 主要 API（JSON，除登录外需 `X-Auth-Token`）

| 方法 | 路径 | 角色 | 说明 |
|---|---|---|---|
| POST | `/api/orders` | 客户 | 下单 |
| POST | `/api/pickup/items/{id}/photos` | 骑手 | 取衣照片（正/反/污渍/洗标/配饰） |
| POST | `/api/pickup-flow/orders/{id}/confirm` | 骑手 | 确认取件：建档+预授权 |
| POST | `/api/pickup-flow/orders/{id}/arrive-store` | 骑手 | 送达门店质检 |
| POST | `/api/pickup-flow/items/{id}/qc-pass` | 门店/工厂 | 质检通过 |
| POST | `/api/exceptions` | 骑手/门店/工厂 | 建异常（附照片/时间/位置），自动规则报价 |
| POST | `/api/exceptions/{id}/evidence` | 全部 | 多次补拍（触发新版本，旧版保留） |
| POST | `/api/exceptions/{id}/assessments` | 全部 | 追加研判（骑手/门店分歧各自保留） |
| POST | `/api/exceptions/{id}/quote` | 门店/客服 | （重新）生成报价版本 |
| GET  | `/api/exceptions/{id}` | 全部 | 异常聚合：证据/研判/报价版本+候选 |
| POST | `/api/decisions/exceptions/{id}/confirm` | 客户 | 确认追加（补授权+占产能+可排产） |
| POST | `/api/decisions/exceptions/{id}/reject` | 客户 | `mode=REFUND` 退回 / `CONTINUE` 继续 |
| POST | `/api/decisions/exceptions/{id}/manual-review` | 客户 | 申请人工复核 |
| POST | `/api/decisions/exceptions/{id}/withdraw` | 客户 | 确认后排产前撤回 |
| GET/POST | `/api/manual-reviews/...` | 客服 | 人工队列与复核结论 |
| POST | `/api/factory/items/{id}/receive|assign-process|start|finish` | 工厂 | 入厂/工艺/洗护/复拍 |
| POST | `/api/ops/orders/{id}/items/{iid}/capture-callback` | 财务/客服 | 按件请款回调（可先失败后同键重试） |
| GET/POST | `/api/payments/...` | 财务/客服 | 支付单、回调事件、失败重试 |
| GET/POST | `/api/recovery/tasks/...` | 骑手 | 回收任务（幂等重派）、完成留影 |
| GET  | `/api/capacity/stores/{id}` | 门店/客服 | 未来 N 天产能占用 |
| POST | `/api/delivery/orders/{id}/deliver` | 配送 | 交付请款+售后 |
| POST | `/api/ops/orders/{id}/closeout` | 客服/财务 | 关单校验+自动结算（缺项转人工） |
| GET/POST | `/api/ops/closeout-blocks/...` | 客服 | 缺项队列与补齐（齐后自动结算） |
| GET  | `/api/dossiers/orders/{id}` | 全部* | **取洗档案聚合**（客户仅限本单） |
| 页面 | `/dossier.html` | 全部 | 取洗档案最终页面（查看+操作） |

\* 客户只能查看自己订单的档案。

## 验证方式（实际执行）

端到端脚本覆盖：取件建档+预授权、材质不符规则 4 候选、多次补拍版本保留、
骑手/门店分歧、客户确认→撤回→再确认、入厂后请款回调失败→同键重试不重复扣款、
拒绝退回的幂等回收、仅基础处理、缺包装阻断关单→人工补齐自动结算、
重复退款回调幂等、跨客户档案越权拒绝、档案页可访问。

```bash
# 终端 1：启动（H2 本地）
mvn spring-boot:run -Ph2
# 终端 2：
BASE=http://localhost:8080 ./verify.sh
# Docker 交付环境：BASE=http://host.docker.internal:3112 ./verify.sh
```

本地实测结果：**PASS=52 FAIL=0**（取件建档/预授权、规则候选可选性、补拍与分歧版本、
确认撤回、回调失败重试与资金幂等、仅基础处理、关单阻断与人工补齐、越权访问、档案页等）。
另手工验证：`mvn clean package -DskipTests` 默认 PostgreSQL 配置打包成功；
重复关单返回 `idempotent=true`；旧工单/赔付链路重复执行赔付返回 409 不重复入账。
