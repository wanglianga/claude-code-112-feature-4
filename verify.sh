#!/usr/bin/env bash
# 取洗档案 · 异常报价决策 · 衣物级状态机 端到端验证
# 用法:
#   本地 H2 : BASE=http://localhost:8080 ./verify.sh
#   Docker   : BASE=http://host.docker.internal:3112 ./verify.sh
set -uo pipefail
BASE="${BASE:-http://localhost:8080}"
jget(){ python3 -c 'import sys,json;d=json.load(sys.stdin);print(eval("d"+sys.argv[1]))' "$1"; }
login(){ curl -s "$BASE/api/auth/login" -H 'Content-Type: application/json' \
  -d "{\"username\":\"$1\",\"password\":\"pass123\"}" | jget "['token']"; }
pass=0; fail=0
check(){ if [[ "$2" == *"$3"* ]]; then echo "  [PASS] $1"; pass=$((pass+1));
  else echo "  [FAIL] $1 -> $2"; fail=$((fail+1)); fi; }
post(){ local b="${5:-}"; curl -s -X "$1" "$BASE$2" -H "X-Auth-Token: $3" ${b:+-H 'Content-Type: application/json' -d "$b"}; }
FUT="2026-12-20T10:00:00"
RUN="r$(date +%s)"   # 每轮唯一，幂等键按轮隔离；同轮内重复调用验证幂等

echo "== 登录各角色 =="
TC=$(login customer1); TP=$(login picker1); TF=$(login factory1)
TS=$(login cs1); TD=$(login courier1); TN=$(login finance1); TM=$(login manager1)
echo "  tokens ok"

# ---------- 订单A：材质不符→追加确认→撤回→再确认→回调失败重试→洗护交付→关单 ----------
echo "== A1. 客户下单 2 件（化纤外套 + 棉质围巾） =="
ORDER=$(post POST /api/orders "$TC" 1 "{
  \"storeId\":1,\"pickupAddress\":\"幸福路1号\",\"deliveryAddress\":\"幸福路1号\",\"expectedDeliveryAt\":\"$FUT\",
  \"items\":[
    {\"type\":\"外套\",\"material\":\"化纤\",\"color\":\"黑\",\"careLabelPresent\":true,\"declaredValue\":800},
    {\"type\":\"围巾\",\"material\":\"棉\",\"color\":\"白\",\"careLabelPresent\":true,\"declaredValue\":100}]}")
OA=$(echo "$ORDER" | jget "['id']")
IT=$(curl -s "$BASE/api/pickup/orders/$OA/items" -H "X-Auth-Token: $TP")
A1=$(echo "$IT" | jget "[0]['id']"); A2=$(echo "$IT" | jget "[1]['id']")
for i in $A1 $A2; do for st in PICKUP_FRONT PICKUP_BACK CARE_LABEL; do
  curl -s "$BASE/api/pickup/items/$i/photos" -H "X-Auth-Token: $TP" -H 'Content-Type: application/json' \
    -d "{\"stage\":\"$st\",\"url\":\"http://img/$i-$st.jpg\"}" >/dev/null
done; done

echo "== A2. 骑手确认取件：取洗档案建立 + 预授权 =="
PK=$(post POST /api/pickup-flow/orders/$OA/confirm "$TP" 1 \
  '{"location":"幸福路1号 GPS:31.2,121.5","customerSign":"张伟","note":"2件"}')
check "取件记录含位置" "$PK" "GPS:31.2"
PAY=$(curl -s "$BASE/api/payments/orders/$OA" -H "X-Auth-Token: $TS")
check "取件即预授权冻结" "$PAY" "PREAUTHED"
check "预授权金额=估价(20+20+配送5=45)" "$PAY" '"preauthAmount":45.00'
post POST /api/pickup-flow/orders/$OA/arrive-store "$TP" >/dev/null

echo "== A3. 门店质检：外套实为皮革（材质不符），建异常附原始证据，系统自动规则报价 =="
EX=$(post POST /api/exceptions "$TM" 1 "{
  \"itemId\":$A1,\"category\":\"MATERIAL_MISMATCH\",\"description\":\"门店质检：外套实际为皮革，非化纤\",
  \"observedMaterial\":\"皮革\",\"suggestedAction\":\"皮具护理并追加差价\",\"severity\":\"MEDIUM\",
  \"photoUrl\":\"http://img/ex-$A1.jpg\",\"capturedAt\":\"2026-10-02T05:00:00\",
  \"location\":\"市中心旗舰店 质检台A\",\"riskLevel\":\"MEDIUM\",\"washable\":true}")
EA=$(echo "$EX" | jget "['id']")
check "异常编号生成" "$EX" "E0000"
check "证据必须附照片/时间/位置" "$EX" "exceptionNo"
ED=$(curl -s "$BASE/api/exceptions/$EA" -H "X-Auth-Token: $TS")
check "自动出具4候选报价" "$ED" "REJECT_ORDER"
check "材质升级皮革→追加60可选" "$ED" '"type":"SURCHARGE"'
check "高风险材质不可维持原价" "$(curl -s "$BASE/api/exceptions/$EA" -H "X-Auth-Token: $TS" | python3 -c 'import sys,json
d=json.load(sys.stdin);o=[x for r in d["rounds"] for x in r["options"] if x["type"]=="KEEP_ORIGINAL_PRICE"][0]
print("KEEP selectable="+str(o["selectable"]))')" "KEEP selectable=False"
check "规则命中留痕(候选/风险口径)" "$ED" "候选=维持原价/追加报价/拒绝承接/仅基础处理"
# 围巾质检通过
post POST /api/pickup-flow/items/$A2/qc-pass "$TM" 1 '{"observedMaterial":"棉","conclusion":"与订单一致"}' >/dev/null

echo "== A4. 多次补拍 → 旧报价版本 SUPERSEDED，新版本不覆盖原判断 =="
EV=$(post POST /api/exceptions/$EA/evidence "$TM" 1 \
  '{"photoUrl":"http://img/reshoot-leather.jpg","capturedAt":"2026-10-02T06:00:00","location":"质检台A","note":"补拍确认皮革纹理"}')
check "补拍证据按轮次留存" "$EV" '"roundNo":2'
ED=$(curl -s "$BASE/api/exceptions/$EA" -H "X-Auth-Token: $TS")
check "补拍触发第2版报价" "$ED" '"version":2'
check "第1版报价被SUPERSEDED保留" "$ED" "SUPERSEDED"

echo "== A5. 客户确认第2版追加报价 → 占产能、允许排产 =="
CF=$(post POST /api/decisions/exceptions/$EA/confirm "$TC" 1 '{"note":"同意按皮革护理"}')
check "客户确认反馈" "$CF" "CONFIRM"
ST=$(curl -s "$BASE/api/pickup/orders/$OA/items" -H "X-Auth-Token: $TP")
check "衣物成交价=80" "$ST" '"finalPrice":80'
check "确认后占用排产" "$ST" "productionDate"
check "订单全部放行→CONFIRMED" "$(curl -s "$BASE/api/orders/$OA" -H "X-Auth-Token: $TC")" '"status":"CONFIRMED"'

echo "== A6. 客户确认后排产前撤回 → 释放产能、旧版作废、重出第3版 =="
WD=$(post POST /api/decisions/exceptions/$EA/withdraw "$TC" 1 '{"note":"想再考虑"}')
check "撤回反馈" "$WD" "WITHDRAW"
ST=$(curl -s "$BASE/api/pickup/orders/$OA/items" -H "X-Auth-Token: $TP")
check "撤回释放产能" "$ST" '"productionDate":null'
check "撤回后重出第3版报价" "$(curl -s "$BASE/api/exceptions/$EA" -H "X-Auth-Token: $TS")" '"version":3'
post POST /api/decisions/exceptions/$EA/confirm "$TC" 1 '{"note":"再次确认"}' >/dev/null

echo "== A7. 入厂洗护；按件请款渠道回调失败→重试幂等（不重复扣款） =="
post POST /api/factory/items/$A1/receive/2 "$TF" >/dev/null
post POST /api/factory/items/$A2/receive/1 "$TF" >/dev/null
P1=$(post POST /api/factory/items/$A1/assign-process "$TF" 1 '{}')
check "皮革→皮具护理工艺" "$P1" "LEATHER_CARE"
post POST /api/factory/items/$A2/assign-process "$TF" 1 '{}' >/dev/null
# 渠道回调先失败（衣物已入厂，资金不动）
CBF=$(post POST /api/ops/orders/$OA/items/$A1/capture-callback "$TN" 1 \
  "{\"idempotencyKey\":\"cap-A1-$RUN\",\"amount\":80,\"success\":false,\"error\":\"渠道超时\"}")
check "失败回调记账" "$CBF" '"callbackFailed":true'
check "支付单进入RETRYING" "$(curl -s "$BASE/api/payments/orders/$OA" -H "X-Auth-Token: $TN")" "RETRYING"
CARD_BEFORE=$(curl -s "$BASE/api/member/card" -H "X-Auth-Token: $TC" | jget "['card']['balance']")
# 同幂等键重放成功
post POST /api/ops/orders/$OA/items/$A1/capture-callback "$TN" 1 \
  "{\"idempotencyKey\":\"cap-A1-$RUN\",\"amount\":80,\"success\":true}" >/dev/null
# 重复成功回调，必须幂等不二次扣款
post POST /api/ops/orders/$OA/items/$A1/capture-callback "$TN" 1 \
  "{\"idempotencyKey\":\"cap-A1-$RUN\",\"amount\":80,\"success\":true}" >/dev/null
CARD_AFTER=$(curl -s "$BASE/api/member/card" -H "X-Auth-Token: $TC" | jget "['card']['balance']")
check "回调重试只扣一次款(余额较回调前-80)" "$(python3 -c "print('delta='+str(float('$CARD_BEFORE')-float('$CARD_AFTER')))")" "delta=80.0"
check "衣物记账请款80" "$(curl -s "$BASE/api/pickup/orders/$OA/items" -H "X-Auth-Token: $TP")" '"capturedAmount":80'

echo "== A8. 洗护复拍 + 包装确认 → 交付 =="
BAL_A_DEL=$(curl -s "$BASE/api/member/card" -H "X-Auth-Token: $TC" | jget "['card']['balance']")
for i in $A1 $A2; do
  post POST /api/factory/items/$i/start "$TF" >/dev/null
  curl -s -X POST "$BASE/api/factory/items/$i/finish" -H "X-Auth-Token: $TF" -H 'Content-Type: application/json' \
    -d "{\"reshootUrl\":\"http://img/reshoot-$i.jpg\"}" >/dev/null
  curl -s -X POST "$BASE/api/factory/items/$i/photos" -H "X-Auth-Token: $TM" -H 'Content-Type: application/json' \
    -d '{"stage":"PACKAGING","url":"http://img/pkg-'$i'.jpg"}' >/dev/null
done
DLV=$(post POST /api/delivery/orders/$OA/deliver "$TD" 1 '{"deliveryPhotoUrl":"http://img/deliver-A.jpg"}')
check "订单交付" "$DLV" "DELIVERED"
check "交付按账单全额请款(80+20+配送5=105)" "$DLV" '"charged":105.00'
BAL_A_DEL2=$(curl -s "$BASE/api/member/card" -H "X-Auth-Token: $TC" | jget "['card']['balance']")
check "交付请款使余额-25(围巾20+配送5)" "$(python3 -c "print('delta='+str(float('$BAL_A_DEL')-float('$BAL_A_DEL2')))")" "delta=25.0"

echo "== A9. 关单：证据/客户确认/质检/包装齐全 → 自动结算 CLOSED =="
CL=$(post POST /api/ops/orders/$OA/closeout "$TS")
check "自动结算成功" "$CL" "SETTLED"
check "无缺项阻断" "$CL" '"blocks":[]'
check "订单关单封存" "$(curl -s "$BASE/api/orders/$OA" -H "X-Auth-Token: $TC")" '"status":"CLOSED"'

# ---------- 订单B：高风险污渍→仅基础处理；缺包装→关单阻断→人工补齐 ----------
echo "== B1. 第二单：高风险污渍衬衫 =="
OB=$(post POST /api/orders "$TC" 1 "{
  \"storeId\":1,\"pickupAddress\":\"人民路2号\",\"deliveryAddress\":\"人民路2号\",\"expectedDeliveryAt\":\"$FUT\",
  \"items\":[{\"type\":\"衬衫\",\"material\":\"棉\",\"color\":\"白\",\"stains\":\"未知黄斑\",\"careLabelPresent\":true,\"declaredValue\":150}]}")
OB=$(echo "$OB" | jget "['id']")
B1=$(curl -s "$BASE/api/pickup/orders/$OB/items" -H "X-Auth-Token: $TP" | jget "[0]['id']")
for st in PICKUP_FRONT PICKUP_BACK STAIN CARE_LABEL; do
  curl -s "$BASE/api/pickup/items/$B1/photos" -H "X-Auth-Token: $TP" -H 'Content-Type: application/json' \
    -d "{\"stage\":\"$st\",\"url\":\"http://img/$B1-$st.jpg\"}" >/dev/null
done
post POST /api/pickup-flow/orders/$OB/confirm "$TP" 1 '{"location":"人民路2号 GPS","customerSign":"张伟"}' >/dev/null
post POST /api/pickup-flow/orders/$OB/arrive-store "$TP" >/dev/null
EB=$(post POST /api/exceptions "$TM" 1 "{
  \"itemId\":$B1,\"category\":\"HIGH_RISK_STAIN\",\"description\":\"大片未知黄斑，高风险串色\",\"observedMaterial\":\"棉\",
  \"suggestedAction\":\"专项去渍或仅基础\",\"severity\":\"HIGH\",\"photoUrl\":\"http://img/stain-$B1.jpg\",
  \"capturedAt\":\"2026-10-02T07:00:00\",\"location\":\"旗舰店质检台B\",\"riskLevel\":\"HIGH\",\"washable\":false,\"suggestedSurcharge\":50}")
EBID=$(echo "$EB" | jget "['id']")
check "高风险污渍不可维持原价" "$(curl -s "$BASE/api/exceptions/$EBID" -H "X-Auth-Token: $TS" | python3 -c 'import sys,json
d=json.load(sys.stdin);o=[x for r in d["rounds"] for x in r["options"] if x["type"]=="KEEP_ORIGINAL_PRICE"][0]
print("KEEP selectable="+str(o["selectable"]))')" "KEEP selectable=False"

echo "== B2. 客户拒绝追加，选择【仅基础处理】继续 =="
RJ=$(post POST /api/decisions/exceptions/$EBID/reject "$TC" 1 '{"mode":"CONTINUE","continueOption":"BASIC_TREATMENT_ONLY","note":"基础洗洗就行"}')
check "客户反馈REJECT_CONTINUE" "$RJ" "REJECT_CONTINUE"
check "衣物进入BASIC_ONLY" "$(curl -s "$BASE/api/pickup/orders/$OB/items" -H "X-Auth-Token: $TP")" "BASIC_ONLY"
post POST /api/factory/items/$B1/receive/1 "$TF" >/dev/null
post POST /api/factory/items/$B1/assign-process "$TF" 1 '{"processType":"BASIC"}' >/dev/null
post POST /api/factory/items/$B1/start "$TF" >/dev/null
curl -s -X POST "$BASE/api/factory/items/$B1/finish" -H "X-Auth-Token: $TF" -H 'Content-Type: application/json' \
  -d '{"reshootUrl":"http://img/reshoot-b.jpg"}' >/dev/null
# 故意不上传 PACKAGING → 交付后关单应阻断
post POST /api/delivery/orders/$OB/deliver "$TD" 1 '{"deliveryPhotoUrl":"http://img/deliver-B.jpg"}' >/dev/null
BL=$(post POST /api/ops/orders/$OB/closeout "$TS")
check "缺包装阻断自动结算" "$BL" "BLOCKED_MANUAL"
check "阻断项=包装记录" "$BL" "PACKAGING"
check "订单转MANUAL_REVIEW" "$(curl -s "$BASE/api/orders/$OB" -H "X-Auth-Token: $TC")" "MANUAL_REVIEW"
BK=$(curl -s "$BASE/api/ops/orders/$OB/closeout-blocks" -H "X-Auth-Token: $TS" | jget "[0]['id']")
# 门店补传包装照片，客服补齐缺项 → 自动重跑结算
curl -s -X POST "$BASE/api/factory/items/$B1/photos" -H "X-Auth-Token: $TM" -H 'Content-Type: application/json' \
  -d '{"stage":"PACKAGING","url":"http://img/pkg-b-late.jpg"}' >/dev/null
post POST /api/ops/closeout-blocks/$BK/resolve "$TS" 1 '{"resolution":"已补传包装照片并核对"}' >/dev/null
check "补齐后自动结算并关单" "$(curl -s "$BASE/api/orders/$OB" -H "X-Auth-Token: $TC")" '"status":"CLOSED"'

# ---------- 订单C：骑手/门店判断不一致 + 客户申请人工复核 + 客服下调 + 拒单回收幂等 ----------
echo "== C1. 第三单：配件缺失异常，骑手与门店研判不一致 =="
OC=$(post POST /api/orders "$TC" 1 "{
  \"storeId\":1,\"pickupAddress\":\"建设路3号\",\"deliveryAddress\":\"建设路3号\",\"expectedDeliveryAt\":\"$FUT\",
  \"items\":[{\"type\":\"风衣\",\"material\":\"棉\",\"accessories\":\"腰带\",\"careLabelPresent\":true,\"declaredValue\":300}]}")
OC=$(echo "$OC" | jget "['id']")
C1=$(curl -s "$BASE/api/pickup/orders/$OC/items" -H "X-Auth-Token: $TP" | jget "[0]['id']")
for st in PICKUP_FRONT PICKUP_BACK CARE_LABEL ACCESSORY; do
  curl -s "$BASE/api/pickup/items/$C1/photos" -H "X-Auth-Token: $TP" -H 'Content-Type: application/json' \
    -d "{\"stage\":\"$st\",\"url\":\"http://img/$C1-$st.jpg\"}" >/dev/null
done
post POST /api/pickup-flow/orders/$OC/confirm "$TP" 1 '{"location":"建设路3号 GPS","customerSign":"张伟"}' >/dev/null
post POST /api/pickup-flow/orders/$OC/arrive-store "$TP" >/dev/null
BAL_C0=$(curl -s "$BASE/api/member/card" -H "X-Auth-Token: $TC" | jget "['card']['balance']")
EC=$(post POST /api/exceptions "$TM" 1 "{
  \"itemId\":$C1,\"category\":\"ACCESSORY_MISSING\",\"description\":\"腰带未在衣袋内\",\"observedMaterial\":\"棉\",
  \"suggestedAction\":\"拆除配件位后正常洗护\",\"severity\":\"MEDIUM\",\"photoUrl\":\"http://img/acc-$C1.jpg\",
  \"capturedAt\":\"2026-10-02T08:00:00\",\"location\":\"旗舰店质检台C\",\"riskLevel\":\"LOW\",\"washable\":true}")
ECID=$(echo "$EC" | jget "['id']")
# 骑手补拍一条不同风险研判（HIGH，与门店 LOW 不一致）
post POST /api/exceptions/$ECID/evidence "$TP" 1 \
  '{"photoUrl":"http://img/acc-rider.jpg","capturedAt":"2026-10-02T08:10:00","location":"客户家门口","note":"骑手翻查袋内确实未见腰带"}' >/dev/null
post POST /api/exceptions/$ECID/assessments "$TP" 1 \
  '{"riskLevel":"HIGH","washable":false,"recommendation":"风险较高建议拒洗","suggestedSurcharge":0}' >/dev/null
check "分歧后双方研判均保留(不覆盖)" "$(curl -s "$BASE/api/exceptions/$ECID" -H "X-Auth-Token: $TS")" '"source":"RIDER"'
check "分歧触发重报新版本" "$(curl -s "$BASE/api/exceptions/$ECID" -H "X-Auth-Token: $TS")" "RIDER_STORE_DISAGREE"

echo "== C2. 客户申请人工复核 → 客服下调追加额 → 出新版报价 =="
post POST /api/decisions/exceptions/$ECID/manual-review "$TC" 1 '{"note":"腰带本来就没给，不该加钱"}' >/dev/null
MR=$(curl -s "$BASE/api/manual-reviews/pending" -H "X-Auth-Token: $TS" | jget "[0]['id']")
HD=$(post POST /api/manual-reviews/$MR/handle "$TS" 1 '{"result":"KEEP_ORIGINAL_PRICE","conclusion":"腰带缺失为客户交付时即无，维持原价"}')
check "人工复核维持原价" "$HD" "KEEP_ORIGINAL_PRICE"
check "衣物复核放行CONFIRMED" "$(curl -s "$BASE/api/pickup/orders/$OC/items" -H "X-Auth-Token: $TP")" '"status":"CONFIRMED"'

echo "== C3. 支付退款回调幂等（同幂等键重复不重复退款） =="
post POST /api/factory/items/$C1/receive/1 "$TF" >/dev/null
# 先请款 20，再发两次相同 REFUND 回调
post POST /api/payments/orders/$OC/callback "$TN" 1 "{\"idempotencyKey\":\"cap-C1-$RUN\",\"type\":\"CAPTURE\",\"amount\":20,\"success\":true}" >/dev/null
BAL1=$(curl -s "$BASE/api/member/card" -H "X-Auth-Token: $TC" | jget "['card']['balance']")
post POST /api/payments/orders/$OC/callback "$TN" 1 "{\"idempotencyKey\":\"ref-C1-$RUN\",\"type\":\"REFUND\",\"amount\":20,\"success\":true}" >/dev/null
post POST /api/payments/orders/$OC/callback "$TN" 1 "{\"idempotencyKey\":\"ref-C1-$RUN\",\"type\":\"REFUND\",\"amount\":20,\"success\":true}" >/dev/null
BAL_C1=$(curl -s "$BASE/api/member/card" -H "X-Auth-Token: $TC" | jget "['card']['balance']")
check "重复退款回调只入账一次(C单请款20又退20，净额0)" "$(python3 -c "print('delta='+str(float('$BAL_C1')-float('$BAL_C0')))")" "delta=0.0"
check "支付单REFUNDED" "$(curl -s "$BASE/api/payments/orders/$OC" -H "X-Auth-Token: $TS")" "REFUNDED"

echo "== D. 取洗档案聚合页数据完整（客服/门店/客户同一事实） =="
DS=$(curl -s "$BASE/api/dossiers/orders/$OA" -H "X-Auth-Token: $TS")
check "档案含取件记录" "$DS" "riderId"
check "档案含异常证据" "$DS" "capturedAt"
check "档案含报价版本" "$DS" "quoteRounds"
check "档案含客户反馈" "$DS" "feedbacks"
check "档案含支付与回调" "$DS" '"preauthAmount"'
check "档案含责任归属" "$DS" "responsibility"
check "档案含审计时间线" "$DS" "QUOTE_ISSUED"
# 客户只能看自己的档案
FORBID=$(curl -s "$BASE/api/dossiers/orders/$OA" -H "X-Auth-Token: $(login customer2)")
check "客户跨单访问被拒" "$FORBID" "只能查看自己订单"
check "档案页面可访问" "$(curl -s "$BASE/dossier.html")" "取洗档案"

echo
echo "================ 结果: PASS=$pass FAIL=$fail ================"
[[ $fail -eq 0 ]]
