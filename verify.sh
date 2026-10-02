#!/usr/bin/env bash
# 端到端验证：下单→拍照→确认→入厂→分配工艺→洗护复拍→多站点流转→高价值复核
#           →交付扣费→异常工单→逐件赔付(含保险)→质检会议复盘→售后申诉
# 用法: BASE=http://host.docker.internal:3112 ./verify.sh
set -euo pipefail
BASE="${BASE:-http://host.docker.internal:3112}"
C=$(command -v jq >/dev/null && echo jq || echo "")

jget() { python3 -c 'import sys,json; d=json.load(sys.stdin); print(eval("d"+sys.argv[1]))' "$1"; }
login() { curl -s "$BASE/api/auth/login" -H 'Content-Type: application/json' \
  -d "{\"username\":\"$1\",\"password\":\"pass123\"}" | jget "['token']"; }

pass=0; fail=0
check() { # check <描述> <实际> <期望子串>
  if [[ "$2" == *"$3"* ]]; then echo "  [PASS] $1"; pass=$((pass+1));
  else echo "  [FAIL] $1 -> $2"; fail=$((fail+1)); fi
}

echo "== 登录各角色 =="
TC=$(login customer1); TP=$(login picker1); TF=$(login factory1)
TS=$(login cs1); TD=$(login courier1); TN=$(login finance1); TM=$(login manager1)
echo "  tokens ok"

echo "== 1. 顾客下单（2 件：衬衫 + 高价值奢侈品皮衣） =="
ORDER=$(curl -s "$BASE/api/orders" -H "X-Auth-Token: $TC" -H 'Content-Type: application/json' -d '{
  "storeId":1,"pickupAddress":"幸福路1号","deliveryAddress":"幸福路1号",
  "expectedDeliveryAt":"2026-10-20T10:00:00",
  "items":[
    {"type":"衬衫","material":"棉","color":"白","stains":"领口污渍","careLabelPresent":true,"declaredValue":200},
    {"type":"皮衣外套","brand":"LV","material":"皮革","color":"黑","accessories":"腰带",
     "careLabelPresent":true,"declaredValue":30000,"luxury":true}
  ]}')
OID=$(echo "$ORDER" | jget "['id']")
echo "  orderId=$OID status=$(echo "$ORDER" | jget "['status']")"
ITEMS=$(curl -s "$BASE/api/pickup/orders/$OID/items" -H "X-Auth-Token: $TP")
I1=$(echo "$ITEMS" | jget "[0]['id']"); I2=$(echo "$ITEMS" | jget "[1]['id']")
C1=$(echo "$ITEMS" | jget "[0]['itemCode']"); C2=$(echo "$ITEMS" | jget "[1]['itemCode']")
echo "  衣物编号: $C1 / $C2"
check "高风险衣物识别" "$ITEMS" "HIGH"

echo "== 2. 高风险接单策略：2号店拒收高风险 =="
BAD=$(curl -s "$BASE/api/orders" -H "X-Auth-Token: $TC" -H 'Content-Type: application/json' -d '{
  "storeId":2,"pickupAddress":"x","deliveryAddress":"x","expectedDeliveryAt":"2026-10-20T10:00:00",
  "items":[{"type":"真丝围巾","material":"真丝","careLabelPresent":true,"declaredValue":100}]}')
check "2号店拒收高风险" "$BAD" "不接收高风险"

echo "== 3. 收衣员逐件拍照（正/反/污渍/洗标；皮衣含配饰） =="
for st in PICKUP_FRONT PICKUP_BACK STAIN CARE_LABEL; do
  curl -s "$BASE/api/pickup/items/$I1/photos" -H "X-Auth-Token: $TP" -H 'Content-Type: application/json' \
    -d "{\"stage\":\"$st\",\"url\":\"http://img/$C1-$st.jpg\"}" >/dev/null
done
for st in PICKUP_FRONT PICKUP_BACK CARE_LABEL ACCESSORY; do
  curl -s "$BASE/api/pickup/items/$I2/photos" -H "X-Auth-Token: $TP" -H 'Content-Type: application/json' \
    -d "{\"stage\":\"$st\",\"url\":\"http://img/$C2-$st.jpg\"}" >/dev/null
done
OD=$(curl -s "$BASE/api/orders/$OID" -H "X-Auth-Token: $TC")
check "拍照齐后订单 PICKED_UP" "$OD" "PICKED_UP"

echo "== 4. 顾客确认 → 进入工厂 =="
CONF=$(curl -s -X POST "$BASE/api/orders/$OID/confirm-pickup" -H "X-Auth-Token: $TC")
check "订单 CONFIRMED" "$CONF" "CONFIRMED"

echo "== 5. 工厂入厂（1号通用站） =="
for i in $I1 $I2; do
  curl -s -X POST "$BASE/api/factory/items/$i/receive/1" -H "X-Auth-Token: $TF" >/dev/null
done
check "订单 IN_FACTORY" "$(curl -s "$BASE/api/orders/$OID" -H "X-Auth-Token: $TF")" "IN_FACTORY"

echo "== 6. 皮衣多站点流转：1号站 → 3号奢侈品站（交接留痕） =="
TR=$(curl -s -X POST "$BASE/api/factory/items/$I2/transfer/3" -H "X-Auth-Token: $TF" \
  -H 'Content-Type: application/json' -d '{"note":"转奢侈品专站护理"}')
check "流转到3号站" "$TR" '"toSiteId":3'

echo "== 7. 自动分配工艺（衬衫→水洗/干洗；皮衣→奢侈品护理） =="
A1=$(curl -s -X POST "$BASE/api/factory/items/$I1/assign-process" -H "X-Auth-Token: $TF" \
  -H 'Content-Type: application/json' -d '{}')
A2=$(curl -s -X POST "$BASE/api/factory/items/$I2/assign-process" -H "X-Auth-Token: $TF" \
  -H 'Content-Type: application/json' -d '{}')
check "衬衫自动工艺" "$A1" "DRY_CLEAN"
check "皮衣奢侈品护理" "$A2" "LUXURY_CARE"

echo "== 8. 洗护完成必须复拍；高价值皮衣进入复核 =="
for i in $I1 $I2; do
  curl -s -X POST "$BASE/api/factory/items/$i/start" -H "X-Auth-Token: $TF" >/dev/null
done
NO_PHOTO=$(curl -s -X POST "$BASE/api/factory/items/$I1/finish" -H "X-Auth-Token: $TF" \
  -H 'Content-Type: application/json' -d '{"note":"完成"}')
check "缺复拍被拒" "$NO_PHOTO" "必须上传复拍照片"
curl -s -X POST "$BASE/api/factory/items/$I1/finish" -H "X-Auth-Token: $TF" \
  -H 'Content-Type: application/json' -d '{"reshootUrl":"http://img/reshoot-1.jpg"}' >/dev/null
curl -s -X POST "$BASE/api/factory/items/$I2/finish" -H "X-Auth-Token: $TF" \
  -H 'Content-Type: application/json' -d '{"reshootUrl":"http://img/reshoot-2.jpg"}' >/dev/null
TRACE2=$(curl -s "$BASE/api/garments/$C2/trace" -H "X-Auth-Token: $TF")
check "高价值进入 IN_REVIEW" "$TRACE2" "IN_REVIEW"

echo "== 9. 复核：缺二次拍照/包装确认不能通过 =="
RV=$(curl -s -X POST "$BASE/api/reviews/items/$I2/complete" -H "X-Auth-Token: $TM" \
  -H 'Content-Type: application/json' -d '{"note":"申请通过"}')
check "缺二次拍照拒绝复核" "$RV" "缺少交付前二次拍照"
curl -s "$BASE/api/factory/items/$I2/photos" -H "X-Auth-Token: $TM" -H 'Content-Type: application/json' \
  -d '{"stage":"SECOND_PHOTO","url":"http://img/second-2.jpg"}' >/dev/null
RV2=$(curl -s -X POST "$BASE/api/reviews/items/$I2/complete" -H "X-Auth-Token: $TM" \
  -H 'Content-Type: application/json' -d '{}')
check "缺包装确认拒绝复核" "$RV2" "缺少包装确认照片"
curl -s "$BASE/api/factory/items/$I2/photos" -H "X-Auth-Token: $TM" -H 'Content-Type: application/json' \
  -d '{"stage":"PACKAGING","url":"http://img/package-2.jpg"}' >/dev/null
curl -s -X POST "$BASE/api/reviews/items/$I2/complete" -H "X-Auth-Token: $TM" \
  -H 'Content-Type: application/json' -d '{"note":"二次拍照+包装确认齐全"}' >/dev/null
check "复核通过订单就绪" "$(curl -s "$BASE/api/delivery/tasks" -H "X-Auth-Token: $TD")" "READY_FOR_DELIVERY"

echo "== 10. 交付：会员扣费 + 逐件售后时限（合并洗护/复拍/赔付摘要） =="
BILL=$(curl -s "$BASE/api/delivery/orders/$OID/bill" -H "X-Auth-Token: $TD")
echo "  账单: 合计=$(echo "$BILL" | jget "['total']")"
DLV=$(curl -s -X POST "$BASE/api/delivery/orders/$OID/deliver" -H "X-Auth-Token: $TD" \
  -H 'Content-Type: application/json' -d '{"deliveryPhotoUrl":"http://img/deliver.jpg"}')
check "交付 DELIVERED" "$DLV" "DELIVERED"
check "逐件售后记录生成" "$DLV" "afterSalesDeadline"
AS=$(curl -s "$BASE/api/delivery/orders/$OID/after-sales" -H "X-Auth-Token: $TC")
check "售后含合并摘要(复拍)" "$AS" "复拍照片"
CARD=$(curl -s "$BASE/api/member/card" -H "X-Auth-Token: $TC")
echo "  交付后会员卡余额: $(echo "$CARD" | jget "['card']['balance']")"

echo "== 11. 售后时限内质疑新破损 → 工单协同（多角色留言） =="
T=$(curl -s -X POST "$BASE/api/orders/items/$I2/claim" -H "X-Auth-Token: $TC" \
  -H 'Content-Type: application/json' -d '{"description":"皮衣背部发现新划痕"}')
TID=$(echo "$T" | jget "['id']")
check "DAMAGE_DISPUTE 工单" "$T" "DAMAGE_DISPUTE"
curl -s -X POST "$BASE/api/tickets/$TID/accept" -H "X-Auth-Token: $TS" >/dev/null
curl -s -X POST "$BASE/api/tickets/$TID/comments" -H "X-Auth-Token: $TF" -H 'Content-Type: application/json' \
  -d '{"content":"工厂核对复拍照片，洗护前未见该划痕"}' >/dev/null
curl -s -X POST "$BASE/api/tickets/$TID/comments" -H "X-Auth-Token: $TD" -H 'Content-Type: application/json' \
  -d '{"content":"配送全程原厂包装无挤压"}' >/dev/null
TCMT=$(curl -s "$BASE/api/tickets/$TID" -H "X-Auth-Token: $TS")
check "多角色围绕同一衣物单留言" "$TCMT" "配送全程原厂包装"

echo "== 12. 逐件赔付：客服立案（皮衣走保险）→ 财务审批 → 赔付入账会员卡 =="
CC=$(curl -s -X POST "$BASE/api/compensations" -H "X-Auth-Token: $TS" -H 'Content-Type: application/json' -d "{
  \"ticketId\":$TID,\"amount\":3000,\"responsibility\":\"FACTORY\",
  \"insuranceClaim\":true,\"insurancePolicyNo\":\"INS-2026-0099\",\"reason\":\"皮衣洗护划痕按保险赔付\"}")
check "保险赔付立案" "$CC" "INS-2026-0099"
CID=$(echo "$CC" | jget "['id']")
BAL_BEFORE=$(echo "$CARD" | jget "['card']['balance']")
curl -s -X POST "$BASE/api/compensations/$CID/approve" -H "X-Auth-Token: $TN" \
  -H 'Content-Type: application/json' -d '{"approve":true}' >/dev/null
PAY=$(curl -s -X POST "$BASE/api/compensations/$CID/pay" -H "X-Auth-Token: $TN")
check "赔付 PAID" "$PAY" "PAID"
CARD2=$(curl -s "$BASE/api/member/card" -H "X-Auth-Token: $TC")
BAL_AFTER=$(echo "$CARD2" | jget "['card']['balance']")
echo "  余额: $BAL_BEFORE → $BAL_AFTER (应 +3000)"
check "赔付入会员卡" "$CARD2" "INS-2026-0099"

echo "== 13. 赔付复盘进入门店质检会议 =="
QM=$(curl -s -X POST "$BASE/api/stores/qc/meetings" -H "X-Auth-Token: $TM" \
  -H 'Content-Type: application/json' -d "{
  \"title\":\"9月第4周门店质检会议\",\"storeId\":1,
  \"conclusion\":\"皮衣护理工艺需增加背部防护垫\",
  \"caseIds\":[$CID]}")
check "质检会议关联赔付案" "$QM" "C00000"
check "赔付案 REVIEWED" "$(curl -s "$BASE/api/compensations/$CID" -H "X-Auth-Token: $TN")" "REVIEWED"

echo "== 14. 单件衣物全链路追溯（独立编号，不混单） =="
TRACE1=$(curl -s "$BASE/api/garments/$C1/trace" -H "X-Auth-Token: $TS")
check "衬衫无皮衣赔付记录(不混赔)" "$(echo "$TRACE1" | python3 -c 'import sys,json;d=json.load(sys.stdin);print(d["compensations"])')" "[]"
check "衬衫有独立售后记录" "$TRACE1" "handoverSummary"

echo
echo "================ 结果: PASS=$pass FAIL=$fail ================"
[[ $fail -eq 0 ]]
