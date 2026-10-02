# 城市干洗店衣物收取洗护与赔付服务

## 原始需求

开发城市干洗店衣物收取洗护与赔付服务，可采用 Java、Spring Boot 和 PostgreSQL。顾客下单后，服务记录衣物类型、品牌、材质、颜色、污渍、破损、配饰、取送地址、会员卡和预计交付时间。收衣员上门拍摄衣物正反面、污渍、标签和配件，顾客确认后进入洗护工厂。工厂根据材质、洗标、污渍类型和风险等级分配干洗、水洗、皮具护理、奢侈品护理或拒洗流程。若洗标缺失、污渍无法去除、衣物缩水、配饰丢失、客户临时改地址、工厂延期或顾客质疑新破损，服务要让顾客、收衣员、工厂、客服、配送和财务围绕同一衣物单处理。交付时，系统合并洗护记录、复拍照片、会员扣费、赔付和售后时限，方便后续追责。服务还要支持奢侈品护理、团体工服、门店自取、保险赔付和工厂多站点流转。每件衣物都需要有独立编号、照片、工艺、交接和售后记录，不能让同一订单里的多件衣物混在一起赔付。高风险衣物会影响门店接单策略。高价值衣物还会进入专人复核流程，交付前必须完成二次拍照和包装确认。赔付复盘会进入门店质检会议。

## 本次增量（feature 4-b：可审计取洗档案 · 异常报价 · 衣物级状态机）

订单进入取件环节后，把客户下单信息、衣物照片、材质/品牌、污渍描述、服务项、估价、骑手取件记录、门店质检、支付状态关联为**同一份可审计取洗档案**，并新增：

- **异常项 + 原始证据**：骑手取件或门店质检发现材质与订单不符、高风险污渍、配件缺失、无法去除瑕疵时，**不能直接加价或继续洗护**；先建异常项并附原始照片、拍摄时间、位置、处理建议。
- **规则候选报价**：系统按规则生成「维持原价 / 追加报价 / 拒绝承接 / 仅基础处理」候选方案；客户在时限（默认 24h）内确认、拒绝或申请人工复核。
- **多版本报价不覆盖原判断**：同一衣物多次补拍、骑手与门店判断不一致均生成新版本，旧版本 `SUPERSEDED` 永久保留。
- **决策联动**：确认追加后才冻结追加预授权并允许排产；拒绝时二选一——**原路退回（骑手重派回收+释放预授权+退款）** 或 **按原服务继续**；决策同步影响骑手回收、门店产能、预授权与逐件预计交付时间。
- **衣物级状态机**：每件衣物独立状态机并落不可变事件流；覆盖多次补拍、骑手/门店分歧、确认后撤回（洗护未开始）、洗护中支付回调失败等场景。
- **幂等**：预授权/扣款/渠道回调/退款/补偿/骑手回收/洗护工序/关单结算全部幂等，杜绝重复扣款、重复退款、重复出库。
- **关单校验**：关单前逐件校验证据、客户确认、质检结论、包装/取回记录、支付结果；缺项即阻断自动结算并转客服人工队列。
- **统一档案页**：`/archive.html` 七段式展示衣物明细、异常证据、报价决策、客户反馈、履约状态、支付/退款、责任归属，供客服/门店/客户核对同一事实。


---

## 技术栈

- Java 17 + Spring Boot 3.3（Web / Data JPA / Actuator）
- PostgreSQL 16（生产 / Compose）；本地无 Docker 时可用内置 **H2（PostgreSQL 兼容模式）** profile 直接运行
- springdoc-openapi：**OpenAPI 3 文档** `GET /v3/api-docs`，**Swagger UI** `GET /swagger-ui.html`
- Maven 多阶段构建，最终镜像基于 `eclipse-temurin:17-jre`，**非 root 用户运行 + HEALTHCHECK**
- 认证：登录发 Token，后续请求携带 `X-Auth-Token`（演示用，无需外部依赖）

## 一键启动（宿主 docker compose）

```bash
cp .env.example .env          # 可按需修改 CC_PUBLISH_PORT
docker compose up -d --build  # 构建并启动 app + PostgreSQL
docker compose ps             # 查看健康状态
```

- 只把**应用服务**端口发布到宿主：`${CC_PUBLISH_PORT}:8080`（默认 3112）。
- **PostgreSQL 不发布宿主端口**，仅在 compose 内部网络通过服务名 `db` 访问。
- 健康检查：`GET /actuator/health` 返回 `{"status":"UP"}`。
- 验证实际映射端口：`docker compose port app 8080`，再通过 `http://host.docker.internal:<端口>` 访问。
- 停止释放资源：`docker compose down`（数据保留）；彻底清理：`docker compose down -v`。
- 启动后：可审计档案页 `http://host.docker.internal:<端口>/archive.html`；Swagger UI `/swagger-ui.html`。

> 验证方式 = 宿主 `docker compose up`，无需在本机安装 JDK/Maven/PostgreSQL。

## 本地开发与验证（无需 Docker）

```bash
# H2 内存库（PostgreSQL 兼容模式），数据重启重建
SPRING_PROFILES_ACTIVE=local mvn spring-boot:run
# 或先打包再运行
mvn -B clean package -DskipTests
java -jar target/city-laundry-1.0.0.jar --spring.profiles.active=local
```

默认端口 8080，演示数据自动初始化（账号见下表）。

端到端验证脚本（应用启动后执行）：

```bash
BASE=http://localhost:8080 ./verify.sh            # 前序全链路回归（25 项检查）
BASE=http://localhost:8080 ./verify-feature4b.sh  # feature 4-b 专项（42 项检查）
```


## 演示账号（逐角色：用户名 / 密码 / 权限）

| 角色 | 用户名 | 密码 | 权限说明 |
|---|---|---|---|
| 顾客 | `customer1` | `pass123` | 下单（会员卡 MC1001 余额 ¥500 GOLD）、确认收衣、查看订单、改地址发起工单、售后质疑新破损 |
| 顾客 | `customer2` | `pass123` | 会员卡 MC1002 余额 ¥200 NORMAL |
| 收衣员 | `picker1` | `pass123` | 看待收衣任务、上传正反面/污渍/洗标/配饰照片 |
| 工厂 | `factory1` | `pass123` | 入厂、分配工艺、洗护、复拍、拒洗、多站点流转、高价值复核拍照 |
| 客服 | `cs1` | `pass123` | 受理/办结异常工单、多角色留言、逐件立案赔付 |
| 配送 | `courier1` | `pass123` | 看待交付订单、执行交付（合并账单+扣费+售后时限） |
| 财务 | `finance1` | `pass123` | 审批赔付、执行赔付/保险赔付、金额划入会员卡 |
| 门店经理 | `manager1` | `pass123` | 调整高风险接单策略、完成高价值专人复核、召开质检会议复盘赔付 |

基础数据：2 家门店（1 号「市中心旗舰店」接收高风险/日限 5 件；2 号「高新社区店」**不接收高风险**）、3 个工厂站点（1 号通用站、2 号皮具站、3 号奢侈品站）。

## 核心业务流

```
顾客下单(记录衣物全量信息+风险评估+门店接单策略)
  → 收衣员上门拍照(正/反/污渍/洗标/配饰)
  → 顾客确认
  → 工厂入厂(交接记录) → 按材质/洗标/污渍/风险分配工艺(干洗/水洗/皮具/奢侈品；洗标缺失自动开异常单)
  → 洗护 → 复拍照片
  → [高价值] 专人复核：二次拍照 + 包装确认齐全才能通过
  → 交付：合并洗护记录/复拍/会员扣费/赔付/售后时限(默认7天)，逐件生成售后记录
  → [异常] 多角色围绕同一件衣物工单协同 → [赔付] 逐件立案(可走保险) → 质检会议复盘
```

关键规则：

- **每件衣物独立编号**（`G<订单号>-<序号>`），照片/工艺/交接/异常/赔付/售后全部按件挂载，赔付严格逐件立案，同订单多件衣物不会混赔。
- **风险评估**：奢侈品 / 声明价值 ≥ ¥5000 / 特殊材质（真丝、皮革、皮草、羊绒）→ 高风险；高风险受门店「是否接单 + 每日上限」策略约束。
- **高价值**（奢侈品或声明价值 ≥ ¥2000）洗护完成后自动进入专人复核，缺二次拍照或包装确认无法通过、无法交付。
- **异常类型**：洗标缺失、污渍无法去除、衣物缩水、配饰丢失、客户临时改地址、工厂延期、顾客质疑新破损（须在售后时限内）。
- **工艺**：干洗 ¥30 / 水洗 ¥20 / 皮具护理 ¥80 / 奢侈品护理 ¥200；团体工服（≥5 件）9 折；门店自取免配送费，配送费 ¥5。
- **保险赔付**：立案时填保险单号，财务审批后状态进入 `INSURANCE_FILED`，执行赔付时金额划入会员卡并标注保单号。
- **多站点流转**：每次流转生成交接记录（起点/终点站点、操作人、时间）。
- **赔付复盘**：已赔付案件进入门店质检会议，会议与赔付案双向关联。

### feature 4-b 关键规则

- **衣物级状态机**（全部迁移落 `garment_status_event` 不可变事件）：
  常规 `REGISTERED→PHOTOGRAPHED→CONFIRMED→AT_FACTORY→PROCESS_ASSIGNED→IN_PROCESS→PROCESS_DONE→IN_REVIEW?→READY_FOR_DELIVERY→DELIVERED→CLOSED`；
  异常分支 `→ANOMALY_QUOTED→（EXTRA_APPROVED / MANUAL_REVIEW / RECLAIM_PENDING→RECLAIMED→RETURNED）`。非法迁移直接 409 拒绝。
- **报价规则**：材质不符按实物工艺差价追加；高风险污渍专项去渍 +¥60/¥120（皮具/奢侈）；配件缺失建议拒收（也可 +¥30 防护/基础处理/维持原价）；不可去除瑕疵默认仅基础处理不加价；洗标缺失可 +¥20 专家判定或基础处理。
- **支付两阶段 + 幂等**：顾客确认收衣即对每件衣物**预授权冻结**基础价（`requestId` 幂等）；交付时逐件 `capture`（`captureIdempotencyKey`）+ 渠道回调（`callbackId` 幂等）；实际工艺价低于预授权时**部分扣款、差额释放**；追加价为独立支付单。拒绝承接释放预授权/原路退回（退款幂等键）。
- **回调失败不回退业务**：衣物已洗护但回调失败时支付停留 `FAILED`、冻结保留，衣物流程不回退；关单校验拦截结算，财务用新流水号重试，只扣一次款。
- **排产门禁**：存在 `OPEN/QUOTED/REVIEW` 异常项的衣物无法分配工艺；确认追加/选择方案且质检闭环后才放行。分配工艺、开始洗护（出库）均幂等。
- **撤回**：客户确认追加后、洗护未开始前可撤回，回到待决策并释放追加预授权；撤回决策与历史报价版本均保留。
- **回收与产能**：拒绝承接生成骑手回收任务（必须留影，完成幂等），衣物离厂即释放站点洗护产能；退回客户自动退款并把订单聚合为 `PARTIALLY_RETURNED`（其余衣物正常交付）。
- **关单校验**（逐件）：证据照片、客户最终决策、入厂质检结论与关单前复检（包装+取回确认）、退回留影/退款结果，任一缺项生成 `settlement_block` 进人工队列，订单不能 `SETTLED`；补齐复检通过后才结算，结算幂等。


## 主要 API（均为 JSON，除登录外需请求头 `X-Auth-Token`）

| 方法 | 路径 | 角色 | 说明 |
|---|---|---|---|
| POST | `/api/auth/login` | - | 登录获取 token |
| GET  | `/api/auth/me` | 全部 | 当前用户信息 |
| POST | `/api/orders` | 顾客 | 下单 |
| GET  | `/api/orders/mine` | 顾客 | 我的订单 |
| GET  | `/api/orders/{id}` | 全部* | 订单详情（逐件衣物+照片+工单+赔付+账单） |
| POST | `/api/orders/{id}/confirm-pickup` | 顾客 | 确认收衣照片 |
| POST | `/api/orders/items/{itemId}/claim` | 顾客 | 售后时限内质疑新破损 |
| GET  | `/api/pickup/tasks` | 收衣员 | 待收衣订单 |
| POST | `/api/pickup/items/{itemId}/photos` | 收衣员 | 上传收衣照片 |
| POST | `/api/factory/items/{itemId}/receive/{siteId}` | 工厂 | 衣物入厂 |
| POST | `/api/factory/items/{itemId}/assign-process` | 工厂 | 分配工艺（空=自动判定） |
| POST | `/api/factory/items/{itemId}/start` | 工厂 | 开始洗护 |
| POST | `/api/factory/items/{itemId}/finish` | 工厂 | 完成洗护（必填复拍 URL） |
| POST | `/api/factory/items/{itemId}/reject` | 工厂 | 拒洗（填原因） |
| POST | `/api/factory/items/{itemId}/transfer/{toSiteId}` | 工厂 | 多站点流转 |
| POST | `/api/factory/items/{itemId}/photos` | 工厂/经理 | 二次拍照、包装确认 |
| POST | `/api/tickets` | 全部 | 对单件衣物发起异常 |
| POST | `/api/tickets/{id}/accept` `/resolve` `/comments` | 客服/全部 | 受理、办结（改地址/延期生效）、留言 |
| POST | `/api/compensations` | 客服 | 基于工单对单件衣物立案（可保险） |
| POST | `/api/compensations/{id}/approve` | 财务 | 审批 |
| POST | `/api/compensations/{id}/pay` | 财务 | 执行赔付入会员卡 |
| GET  | `/api/reviews/pending` | 经理/工厂 | 待高价值复核 |
| POST | `/api/reviews/items/{itemId}/complete` | 经理/工厂 | 复核（校验两照齐全） |
| GET  | `/api/delivery/tasks` | 配送/经理 | 待交付订单 |
| POST | `/api/delivery/orders/{id}/deliver` | 配送/经理 | 交付：扣费+逐件售后记录 |
| GET  | `/api/delivery/orders/{id}/bill` | 全部 | 账单预览 |
| GET  | `/api/garments/{itemCode}/trace` | 全部 | 单件衣物全链路追溯 |
| PUT  | `/api/stores/{id}/strategy` | 经理 | 高风险接单策略 |
| POST | `/api/stores/qc/meetings` | 经理 | 质检会议（赔付复盘） |
| GET  | `/api/member/card` / POST `/api/member/recharge` | 顾客 | 会员卡/充值 |

### feature 4-b 新增 API

| 方法 | 路径 | 角色 | 说明 |
|---|---|---|---|
| POST | `/api/issues` | 骑手/工厂/经理/客服 | 登记异常项（材质不符/高风险污渍/配件缺失/不可去除瑕疵）+原始照片/时间/位置/建议，自动生成候选报价并阻塞排产 |
| POST | `/api/issues/{id}/evidence` | 骑手/工厂/经理/客服 | 多次补拍/骑手门店分歧证据（`reprice` 触发新版报价，原判断保留） |
| GET  | `/api/issues/{id}` | 全部 | 异常项+证据+全部报价版本+客户决策 |
| POST | `/api/issues/{id}/decision` | 顾客 | `CONFIRM_EXTRA`/`REJECT_REFUND`/`REJECT_CONTINUE`/`BASIC_ONLY`/`REQUEST_REVIEW` |
| POST | `/api/issues/{id}/withdraw` | 顾客 | 确认后、洗护未开始前撤回（释放追加预授权） |
| GET  | `/api/issues/reviews/pending` | 客服/经理 | 人工复核队列 |
| POST | `/api/issues/reviews/{id}/verdict` | 客服/经理 | 裁决：维持/改价（出新版）/仅基础处理/拒绝承接 |
| POST | `/api/issues/expire-sweep` | 客服/经理 | 超时未决策→报价失效并转人工队列 |
| POST | `/api/store-qc/items/{itemId}/intake` | 工厂/经理/客服 | 入厂质检结论 |
| POST | `/api/store-qc/items/{itemId}/final` | 经理/工厂/客服/配送 | 关单前复检（包装+取回确认） |
| GET  | `/api/fulfillment/reclaims/tasks` | 配送/骑手/经理/客服 | 回收任务列表 |
| POST | `/api/fulfillment/reclaims/{id}/assign` | 配送/骑手/经理/客服 | 重派骑手 |
| POST | `/api/fulfillment/reclaims/{id}/complete` | 配送/骑手/经理 | 完成回收（必须留影，幂等不重复出库） |
| POST | `/api/fulfillment/items/{itemId}/return` | 配送/骑手/经理/客服/财务 | 退回客户并原路退回（幂等） |
| GET  | `/api/fulfillment/capacity?siteId=` | 经理/工厂/客服 | 站点产能占用（拒绝/回收即时释放） |
| POST | `/api/payments/{id}/capture` | 财务/配送/经理 | 发起扣款（幂等键） |
| POST | `/api/payments/{id}/callback` | 财务/配送/经理 | 渠道回调（`callbackId` 幂等；失败可换新流水号重试，只扣一次） |
| POST | `/api/payments/{id}/refund` | 财务/客服/经理 | 原路退回/释放（幂等键） |
| GET  | `/api/payments/item/{itemId}` | 全部 | 单件支付链路 |
| GET  | `/api/settlement/items/{itemId}/check` | 全部 | 单件关单校验（返回缺项明细） |
| POST | `/api/settlement/close` | 经理/客服/财务 | 执行关单自动结算（缺项阻断；幂等） |
| GET  | `/api/settlement/manual-queue` | 客服/经理/财务 | 关单阻断人工队列 |
| POST | `/api/settlement/blocks/{id}/handle` `/resolve` | 客服/经理 | 跟进/补齐后解除阻断 |
| GET  | `/api/archive/items/{itemId}` | 全部 | 单件可审计取洗档案（JSON） |
| GET  | `/api/archive/orders/{orderId}` | 全部 | 订单取洗档案（逐件聚合，JSON） |
| GET  | `/archive.html` | 浏览器 | 可审计取洗档案页面（七段式事实核对） |
| GET  | `/swagger-ui.html` · `/v3/api-docs` | - | OpenAPI 3 文档与 Swagger UI |

\* 顾客只能查看自己的订单。

### curl 快速验证（假设映射端口 3112）

```bash
BASE=http://host.docker.internal:3112
TOKEN=$(curl -s $BASE/api/auth/login -H 'Content-Type: application/json' \
  -d '{"username":"customer1","password":"pass123"}' | python3 -c 'import sys,json;print(json.load(sys.stdin)["token"])')

# 顾客下单：普通衬衫 + 高价值奢侈品皮衣（触发高风险接单策略 + 高价值复核）
curl -s $BASE/api/orders -H "X-Auth-Token: $TOKEN" -H 'Content-Type: application/json' -d '{
  "storeId": 1,
  "pickupAddress": "幸福路1号",
  "deliveryAddress": "幸福路1号",
  "expectedDeliveryAt": "2026-10-20T10:00:00",
  "items": [
    {"type":"衬衫","material":"棉","color":"白","stains":"领口污渍","careLabelPresent":true,"declaredValue":200},
    {"type":"皮衣外套","brand":"LV","material":"皮革","color":"黑","accessories":"腰带",
     "careLabelPresent":true,"declaredValue":30000,"luxury":true}
  ]
}'
```

完整端到端脚本见仓库 `verify.sh`（下单→拍照→确认→入厂→分配工艺→洗护复拍→多站点流转→高价值复核→交付扣费→异常工单→逐件赔付/保险赔付→质检会议复盘）。

feature 4-b 专项脚本 `verify-feature4b.sh` 覆盖：门店/骑手异常建档与排产门禁、多版本报价与补拍分歧、确认/撤回/重确认的预授权联动、支付回调失败拦截与幂等重试、关单缺项阻断与补齐结算、拒绝承接-骑手回收-原路退回-产能释放、人工复核改价，以及取件现场异常按原服务继续、仅基础处理等分支。

## 实际验证结论（本次交付已执行）

环境：本机 `openjdk 17` + Maven，`SPRING_PROFILES_ACTIVE=local`（内置 H2 PostgreSQL 兼容模式），应用健康检查 `GET /actuator/health` 返回 `{"status":"UP"}`。

| 验证项 | 命令 | 结果 |
|---|---|---|
| 编译打包（等同 Docker 构建步骤） | `mvn -B clean package -DskipTests` | 成功，产出 `target/city-laundry-1.0.0.jar` |
| 前序全链路回归 | `BASE=http://localhost:8080 ./verify.sh` | **PASS=25 FAIL=0** |
| feature 4-b 专项 | `BASE=http://localhost:8080 ./verify-feature4b.sh` | **PASS=42 FAIL=0** |
| 取件现场异常/维持原价/仅基础处理补充分支 | 脚本化 API 调用 | **PASS=7 FAIL=0** |
| 取件现场确认追加→补传照片→确认收衣入厂 | 脚本化 API 调用 | **PASS=6 FAIL=0** |
| OpenAPI / Swagger UI | `GET /v3/api-docs`（OpenAPI 3.0.1，73 个路径）、`/swagger-ui.html`（302→200） | 可访问 |
| 可审计档案页 | `GET /archive.html` | 200 |

重点实测行为：异常未决时分配工艺返回 409；补拍生成 v2 且 v1 置 `SUPERSEDED`；确认追加冻结、撤回释放、重新确认再冻结；交付模拟渠道回调失败后关单被阻断，换新流水号重试成功且重复回调/重复扣款均幂等；缺包装复检时结算 BLOCKED 并进人工队列，复检 PASS 后订单 SETTLED、重复结算返回 ALREADY_SETTLED；拒绝承接的回收留影缺失被拒、重复回收/退回不重复出库与退款。

> 说明：交付物以宿主 `docker compose up`（PostgreSQL 16）为标准启动方式；因当前环境无 Docker 守护进程，以上验证在等价的 H2(PostgreSQL 兼容模式) 本地 profile 上实际执行，SQL 均为 JPA 通用 DDL，未使用数据库专有语法。
