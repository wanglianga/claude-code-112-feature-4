#!/usr/bin/env bash
# feature 4-b 端到端验证：
#   可审计取洗档案 / 异常项+证据 / 多版本候选报价 / 客户决策与撤回 / 人工复核 /
#   预授权-扣款-回调幂等 / 拒绝承接-骑手回收-原路退回 / 多次补拍与骑手门店分歧 /
#   排产门禁 / 关单校验缺项阻断 / 支付回调失败拦截与恢复 / 重复扣款退款幂等
# 用法: BASE=http://localhost:8080 ./verify-feature4b.sh
set -uo pipefail
BASE="${BASE:-http://localhost:8080}"
jget() { python3 -c 'import sys,json; d=json.load(sys.stdin); print(eval("d"+sys.argv[1]))' "$1"; }
login() { curl -s "$BASE/api/auth/login" -H 'Content-Type: application/json' \
  -d "{\"username\":\"$1\",\"password\":\"pass123\"}" | jget "['token']"; }
pass=0; fail=0
check() { if [[ "$2" == *"$3"* ]]; then echo "  [PASS] $1"; pass=$((pass+1));
  else echo "  [FAIL] $1 -> $2"; fail=$((fail+1)); fi; }
post() { curl -s -X POST "$BASE$1" -H "X-Auth-Token: $2" -H 'Content-Type: application/json' -d "${3:-}"; }
get() { curl -s "$BASE$1" -H "X-Auth-Token: $2"; }

TC=$(login customer1); TP=$(login picker1); TF=$(login factory1)
TS=$(login cs1); TD=$(login courier1); TN=$(login finance1); TM=$(login manager1)
echo "登录成功"

echo "== A. 门店质检发现「材质不符」：异常项+证据→候选报价，禁止直接加价/排产 =="
O=$(post /api/orders "$TC" '{"storeId":1,"pickupAddress":"A路1号","deliveryAddress":"A路1号","expectedDeliveryAt":"2026-10-20T10:00:00",
  "items":[{"type":"外套","brand":"某牌","material":"化纤","color":"蓝","stains":"袖口油污","careLabelPresent":true,"declaredValue":800}]}')
OID=$(echo "$O" | jget "['id']")
II=$(get "/api/pickup/orders/$OID/items" "$TP" | jget "[0]['id']")
for st in PICKUP_FRONT PICKUP_BACK STAIN CARE_LABEL; do
  post "/api/pickup/items/$II/photos" "$TP" "{\"stage\":\"$st\",\"url\":\"http://img/a-$st.jpg\"}" >/dev/null
done
post "/api/orders/$OID/confirm-pickup" "$TC" >/dev/null
post "/api/factory/items/$II/receive/1" "$TF" >/dev/null
# 尝试直接排产：此时还没异常，先成功分配？不——先由门店质检登记异常（在分配前）
ISS=$(post /api/issues "$TF" "{\"itemId\":$II,\"type\":\"MATERIAL_MISMATCH\",\"description\":\"实物为皮革，与订单化纤不符\",
  \"severity\":\"HIGH\",\"suggestion\":\"应改皮具护理\",\"actualMaterial\":\"皮革\",\"actualBrand\":\"某牌\",
  \"photoUrl\":\"http://img/anomaly-1.jpg\",\"location\":\"门店1号质检台\"}")
ISID=$(echo "$ISS" | jget "['id']")
check "异常项 QUOTED" "$ISS" "QUOTED"
check "衣物阻塞 ANOMALY_QUOTED" "$(get "/api/archive/items/$II" "$TF")" "ANOMALY_QUOTED"
BLOCK=$(post "/api/factory/items/$II/assign-process" "$TF" '{}')
check "异常未决禁止排产" "$BLOCK" "禁止"
QV=$(get "/api/issues/$ISID" "$TC")
check "生成候选报价(含追加/拒绝)" "$QV" "EXTRA_CHARGE"
check "证据含拍摄时间/位置" "$QV" "门店1号质检台"
echo "  报价版本数: $(echo "$QV" | python3 -c 'import sys,json;print(len(json.load(sys.stdin)["quoteVersions"]))')"

echo "== B. 多次补拍 + 骑手/门店判断不一致：生成新版报价，原版本保留 =="
post "/api/issues/$ISID/evidence" "$TP" "{\"photoUrl\":\"http://img/re-shot-2.jpg\",\"sourceStage\":\"PICKUP\",
  \"disagreementNote\":\"骑手认为仍是化纤涂层面料，非真皮\",\"suggestion\":\"维持水洗\"}" >/dev/null
QV2=$(get "/api/issues/$ISID" "$TC")
check "补拍产生 v2 报价" "$QV2" '"versionNo":2'
check "原 v1 被 SUPERSEDED 保留" "$QV2" "SUPERSEDED"
check "分歧说明留痕" "$QV2" "非真皮"

echo "== C. 客户确认追加 → 预授权冻结 → 可撤回（释放冻结）→ 重新确认 =="
CARD0=$(get /api/member/card "$TC" | jget "['card']['frozenBalance']")
post "/api/issues/$ISID/decision" "$TC" '{"action":"CONFIRM_EXTRA","note":"同意皮具护理差价"}' >/dev/null
AR=$(get "/api/archive/items/$II" "$TC")
check "确认后状态 EXTRA_APPROVED" "$AR" "EXTRA_APPROVED"
CARD1=$(get /api/member/card "$TC" | jget "['card']['frozenBalance']")
echo "  冻结: $CARD0 → $CARD1（应增加追加额）"
check "追加预授权已冻结" "$(python3 -c "print(float('$CARD1')>float('$CARD0'))")" "True"
# 撤回
post "/api/issues/$ISID/withdraw" "$TC" '{"note":"再考虑下"}' >/dev/null
AR2=$(get "/api/archive/items/$II" "$TC")
check "撤回后回到 ANOMALY_QUOTED" "$AR2" "ANOMALY_QUOTED"
check "撤回决策留痕 WITHDRAWN" "$AR2" "WITHDRAWN"
CARD2=$(get /api/member/card "$TC" | jget "['card']['frozenBalance']")
check "撤回释放追加冻结" "$(python3 -c "print(abs(float('$CARD2')-float('$CARD0'))<0.01)")" "True"
# 重新确认
post "/api/issues/$ISID/decision" "$TC" '{"action":"CONFIRM_EXTRA","note":"确认追加"}' >/dev/null
check "重新确认 EXTRA_APPROVED" "$(get "/api/archive/items/$II" "$TC")" "EXTRA_APPROVED"

echo "== D. 确认后允许排产洗护；追加价并入账单 =="
post "/api/factory/items/$II/assign-process" "$TF" '{}' >/dev/null
# 排产幂等：再次分配返回同一工艺，不重复建单
N1=$(get "/api/factory/items/$II/processes" "$TF" | python3 -c 'import sys,json;print(len(json.load(sys.stdin)))')
post "/api/factory/items/$II/assign-process" "$TF" '{}' >/dev/null
N2=$(get "/api/factory/items/$II/processes" "$TF" | python3 -c 'import sys,json;print(len(json.load(sys.stdin)))')
check "分配工艺幂等(不重复出库)" "$N1-$N2" "$N1-$N1"
post "/api/factory/items/$II/start" "$TF" >/dev/null
post "/api/factory/items/$II/finish" "$TF" '{"reshootUrl":"http://img/reshoot-a.jpg"}' >/dev/null
BILL=$(get "/api/delivery/orders/$OID/bill" "$TD")
check "账单含追加金额行" "$BILL" "agreedExtra"
echo "  账单合计: $(echo "$BILL" | jget "['total']")"

echo "== E. 支付回调失败：关单校验阻断；恢复后扣款成功且不重复扣款 =="
DLV=$(post "/api/delivery/orders/$OID/deliver" "$TD" "{\"deliveryPhotoUrl\":\"http://img/dlv.jpg\",\"simulateFailItemId\":$II}")
check "交付完成但存在失败支付" "$DLV" "FAILED"
# 重试该件全部失败支付单（基础+追加），每件只扣一次
for PID in $(get "/api/payments/item/$II" "$TN" | python3 -c 'import sys,json;print(" ".join(str(p["id"]) for p in json.load(sys.stdin) if p["status"]=="FAILED"))'); do
  CB=$(post "/api/payments/$PID/callback" "$TN" '{"callbackId":"CB-RETRY-'$PID'-1","success":true}')
  check "支付单 $PID 重试回调成功" "$CB" "CAPTURED"
  CB2=$(post "/api/payments/$PID/callback" "$TN" '{"callbackId":"CB-RETRY-'$PID'-1","success":true}')
  check "支付单 $PID 相同流水号幂等" "$CB2" "CAPTURED"
done
PAYID=$(get "/api/payments/item/$II" "$TN" | python3 -c 'import sys,json;ps=json.load(sys.stdin);print([p["id"] for p in ps if p["bizRef"] and p["bizRef"].startswith("BASE")][0])')
# 重复扣款尝试
CAPX=$(post "/api/payments/$PAYID/capture" "$TN" '{"idempotencyKey":"anykey"}')
check "已扣款不可重复发起" "$CAPX" "CAPTURED"

echo "== F. 关单校验：缺复检/包装 → 阻断结算 → 补齐后 SETTLED =="
CHK=$(get "/api/settlement/items/$II/check" "$TS")
check "未复检关单被阻断" "$CHK" "false"
CLOSE1=$(post /api/settlement/close "$TS" "{\"orderId\":$OID}")
check "自动结算 BLOCKED" "$CLOSE1" "BLOCKED"
check "进入人工队列" "$(get /api/settlement/manual-queue "$TS")" "missingItems"
# 补齐：复检包装+交接
post "/api/factory/items/$II/photos" "$TM" '{"stage":"PACKAGING","url":"http://img/pkg-a.jpg"}' >/dev/null
post "/api/store-qc/items/$II/final" "$TM" '{"packagingConfirmed":true,"handoverConfirmed":true,"note":"包装与交接齐全"}' >/dev/null
CHK2=$(get "/api/settlement/items/$II/check" "$TS")
check "补齐后关单校验通过" "$CHK2" '"passed":true'
CLOSE2=$(post /api/settlement/close "$TS" "{\"orderId\":$OID}")
check "订单 SETTLED" "$CLOSE2" "SETTLED"
# 重复结算幂等
CLOSE3=$(post /api/settlement/close "$TS" "{\"orderId\":$OID}")
check "重复结算幂等" "$CLOSE3" "ALREADY_SETTLED"

echo
echo "== G. 拒绝承接→骑手回收→原路退回（影响回收/产能/预授权/ETA），另一单 =="
O2=$(post /api/orders "$TC" '{"storeId":1,"pickupAddress":"B路2号","deliveryAddress":"B路2号","expectedDeliveryAt":"2026-10-21T10:00:00",
  "items":[{"type":"衬衫","material":"棉","careLabelPresent":true,"declaredValue":100}]}')
O2ID=$(echo "$O2" | jget "['id']")
J2=$(get "/api/pickup/orders/$O2ID/items" "$TP" | jget "[0]['id']")
for st in PICKUP_FRONT PICKUP_BACK CARE_LABEL; do
  post "/api/pickup/items/$J2/photos" "$TP" "{\"stage\":\"$st\",\"url\":\"http://img/b-$st.jpg\"}" >/dev/null; done
FB=$(get /api/member/card "$TC" | jget "['card']['frozenBalance']")
post "/api/orders/$O2ID/confirm-pickup" "$TC" >/dev/null
post "/api/factory/items/$J2/receive/1" "$TF" >/dev/null
IS2=$(post /api/issues "$TF" "{\"itemId\":$J2,\"type\":\"HIGH_RISK_STAIN\",\"description\":\"发现强腐蚀性高风险污渍\",
  \"severity\":\"HIGH\",\"suggestion\":\"建议拒收\",\"photoUrl\":\"http://img/risk-1.jpg\",\"location\":\"门店质检台\"}")
IS2ID=$(echo "$IS2" | jget "['id']")
post "/api/issues/$IS2ID/decision" "$TC" '{"action":"REJECT_REFUND","note":"不敢洗，退"}' >/dev/null
AR3=$(get "/api/archive/items/$J2" "$TC")
check "衣物 RECLAIM_PENDING" "$AR3" "RECLAIM_PENDING"
check "生成骑手回收任务" "$AR3" "reclaimNo"
RCID=$(echo "$AR3" | jget "['reclaim']['id']")
# 无留影完成回收应被拒
NOPH=$(post "/api/fulfillment/reclaims/$RCID/complete" "$TD" '{"handoverPhotoUrl":""}')
check "回收必须留影" "$NOPH" "交接留影"
post "/api/fulfillment/reclaims/$RCID/assign" "$TD" '{"note":"重派骑手刘洋"}' >/dev/null
post "/api/fulfillment/reclaims/$RCID/complete" "$TD" '{"handoverPhotoUrl":"http://img/reclaim-1.jpg","note":"已取走"}' >/dev/null
# 回收幂等：重复完成不重复出库
RC2=$(post "/api/fulfillment/reclaims/$RCID/complete" "$TD" '{"handoverPhotoUrl":"http://img/reclaim-1.jpg"}')
check "回收完成幂等" "$RC2" "RECLAIMED"
post "/api/fulfillment/items/$J2/return" "$TD" '{"note":"退回客户"}' >/dev/null
AR4=$(get "/api/archive/items/$J2" "$TC")
check "衣物 RETURNED" "$AR4" "RETURNED"
check "预授权已释放(RELEASED)" "$AR4" "RELEASED"
FA=$(get /api/member/card "$TC" | jget "['card']['frozenBalance']")
check "冻结额回落到拒绝前" "$(python3 -c "print(abs(float('$FA')-float('$FB'))<0.01)")" "True"
# 退回幂等
RT2=$(post "/api/fulfillment/items/$J2/return" "$TD" '{}')
check "退回退款幂等(不重复退款)" "$RT2" "RETURNED"
# 档案完整：异常/证据/报价/决策/回收/支付责任七段
check "档案含责任归属段" "$AR4" "responsibility"
check "档案含状态机事件流" "$AR4" "RETURN_TO_CUSTOMER"

echo
echo "== H. 人工复核：客户申请→客服改价出新版→客户确认后排产 =="
O3=$(post /api/orders "$TC" '{"storeId":1,"pickupAddress":"C路3号","deliveryAddress":"C路3号","expectedDeliveryAt":"2026-10-22T10:00:00",
  "items":[{"type":"丝巾","material":"真丝","careLabelPresent":true,"declaredValue":1500,"luxury":false}]}')
O3ID=$(echo "$O3" | jget "['id']")
J3=$(get "/api/pickup/orders/$O3ID/items" "$TP" | jget "[0]['id']")
for st in PICKUP_FRONT PICKUP_BACK CARE_LABEL; do
  post "/api/pickup/items/$J3/photos" "$TP" "{\"stage\":\"$st\",\"url\":\"http://img/c-$st.jpg\"}" >/dev/null; done
post "/api/orders/$O3ID/confirm-pickup" "$TC" >/dev/null
post "/api/factory/items/$J3/receive/1" "$TF" >/dev/null
IS3=$(post /api/issues "$TF" "{\"itemId\":$J3,\"type\":\"UNREMOVABLE_FLAW\",\"description\":\"顽渍可能无法完全去除\",
  \"severity\":\"MEDIUM\",\"suggestion\":\"基础处理\",\"photoUrl\":\"http://img/flaw-1.jpg\"}")
IS3ID=$(echo "$IS3" | jget "['id']")
post "/api/issues/$IS3ID/decision" "$TC" '{"action":"REQUEST_REVIEW","note":"我要求人工看看"}' >/dev/null
check "进入 MANUAL_REVIEW" "$(get "/api/archive/items/$J3" "$TC")" "MANUAL_REVIEW"
RID=$(get /api/issues/reviews/pending "$TS" | python3 -c 'import sys,json;rs=json.load(sys.stdin);print([r["id"] for r in rs if r["itemId"]=='"$J3"'][0])')
post "/api/issues/reviews/$RID/verdict" "$TS" '{"outcome":"ADJUST_QUOTE","adjustedExtra":10,"note":"复核后仅加10元专项费"}' >/dev/null
QV3=$(get "/api/issues/$IS3ID" "$TC")
check "人工裁决产生新版本" "$QV3" "MANUAL_REVIEW"
check "回到待客户决策" "$(get "/api/archive/items/$J3" "$TC")" "ANOMALY_QUOTED"
post "/api/issues/$IS3ID/decision" "$TC" '{"action":"CONFIRM_EXTRA","note":"同意10元"}' >/dev/null
post "/api/factory/items/$J3/assign-process" "$TF" '{}' >/dev/null
check "裁决确认后可排产" "$(get "/api/archive/items/$J3" "$TF")" "PROCESS_ASSIGNED"

echo
echo "================ feature4b 结果: PASS=$pass FAIL=$fail ================"
[[ $fail -eq 0 ]]
