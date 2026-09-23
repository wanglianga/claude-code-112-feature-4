# 城市干洗店衣物收取洗护与赔付服务

## 原始需求

开发城市干洗店衣物收取洗护与赔付服务，可采用 Java、Spring Boot 和 PostgreSQL。顾客下单后，服务记录衣物类型、品牌、材质、颜色、污渍、破损、配饰、取送地址、会员卡和预计交付时间。收衣员上门拍摄衣物正反面、污渍、标签和配件，顾客确认后进入洗护工厂。工厂根据材质、洗标、污渍类型和风险等级分配干洗、水洗、皮具护理、奢侈品护理或拒洗流程。若洗标缺失、污渍无法去除、衣物缩水、配饰丢失、客户临时改地址、工厂延期或顾客质疑新破损，服务要让顾客、收衣员、工厂、客服、配送和财务围绕同一衣物单处理。交付时，系统合并洗护记录、复拍照片、会员扣费、赔付和售后时限，方便后续追责。服务还要支持奢侈品护理、团体工服、门店自取、保险赔付和工厂多站点流转。每件衣物都需要有独立编号、照片、工艺、交接和售后记录，不能让同一订单里的多件衣物混在一起赔付。高风险衣物会影响门店接单策略。高价值衣物还会进入专人复核流程，交付前必须完成二次拍照和包装确认。赔付复盘会进入门店质检会议。

---

## 技术栈

- Java 17 + Spring Boot 3.3（Web / Data JPA / Actuator）
- PostgreSQL 16
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

> 验证方式 = 宿主 `docker compose up`，无需在本机安装 JDK/Maven/PostgreSQL。

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
  "expectedDeliveryAt": "2026-09-30T10:00:00",
  "items": [
    {"type":"衬衫","material":"棉","color":"白","stains":"领口污渍","careLabelPresent":true,"declaredValue":200},
    {"type":"皮衣外套","brand":"LV","material":"皮革","color":"黑","accessories":"腰带",
     "careLabelPresent":true,"declaredValue":30000,"luxury":true}
  ]
}'
```

完整端到端脚本见仓库 `verify.sh`（下单→拍照→确认→入厂→分配工艺→洗护复拍→多站点流转→高价值复核→交付扣费→异常工单→逐件赔付/保险赔付→质检会议复盘）。
