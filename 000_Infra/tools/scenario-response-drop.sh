#!/usr/bin/env bash
# 카오스 시나리오 — 소비자 응답 드롭. 결과표(README 1.2)의 "소비자 응답 드롭" 줄.
#
#   docker compose up -d --build
#   000_Infra/tools/scenario-response-drop.sh [건수=100] [드롭률=0.3]
#
# 소비자가 결제를 끝낸 뒤 응답을 끊는다. 게이트웨이는 그걸 실패(CONN_RESET)로 보고 다시 보내고,
# 소비자는 같은 X-Gateway-Event-Id 라 결제를 건너뛴다.
#
# 기대값    유실 0 · 중복 전달 N(>0) · 중복 처리 0
#
# 열 계산   유실       = 보낸 건수 - DELIVERED
#           중복 전달  = 소비자에 닿은 시도(SUCCESS + CONN_RESET) - 보낸 건수
#           중복 처리  = 실제 결제 횟수(잔액 감소 / 금액) - 보낸 건수. 모자라면 0 이고 아래 정합성으로 따로 본다
# 대조      게이트웨이 CONN_RESET 건수 = 소비자가 센 드롭 횟수
# 정합성    처리 기록 건수 = 실제 결제 횟수. 결제가 적으면 동시 결제에서 차감이 사라진 것(lost update)이다
#
# 결제 횟수를 처리 기록이 아니라 잔액으로 센다. 처리 기록만 보면 차감이 사라져도 모른다.

set -euo pipefail

N="${1:-100}"
RATE="${2:-0.3}"
GATEWAY="${GATEWAY:-http://localhost:8080}"
SLUG="${SLUG:-svc-drop}"
SECRET="${SECRET:-topsecret-secret-1234}"
AMOUNT=1000
START_BALANCE=10000000
WAIT_SECONDS="${WAIT_SECONDS:-180}"
RUN="drop-$(date +%s)"

MYSQL="docker exec -i -e MYSQL_PWD=root wg-mysql mysql -uroot"
CHAOS_URL="localhost:8081/chaos/response-drop"

say() { printf '\n\033[1m%s\033[0m\n' "$*"; }
q() { $MYSQL -N "$1" -e "$2" | tr -d '\r'; }
chaos() { docker exec wg-service curl -s "$@"; }

# 중간에 실패해도 드롭을 켠 채로 두지 않는다.
trap 'chaos -X POST "${CHAOS_URL}?rate=0" > /dev/null 2>&1 || true' EXIT

say "1) 소비자 시드 — 잔액 ${START_BALANCE}, 주문 123번 ${AMOUNT}원, 처리 기록 비움"
$MYSQL webhook_service <<SQL
INSERT INTO account (id, balance) VALUES (1, ${START_BALANCE})
  ON DUPLICATE KEY UPDATE balance = ${START_BALANCE};
INSERT INTO orders (id, account_id, amount) VALUES (123, 1, ${AMOUNT})
  ON DUPLICATE KEY UPDATE amount = ${AMOUNT};
DELETE FROM processed_event;
SQL

say "2) 엔드포인트 ${SLUG} → http://service:8081/webhook/receiver"
code=$(curl -sS -o /dev/null -w '%{http_code}' -X POST "${GATEWAY}/admin/endpoints" \
  -H 'Content-Type: application/json' \
  -d "{\"slug\":\"${SLUG}\",\"provider\":\"github\",\"secret\":\"${SECRET}\",\"targetUrl\":\"http://service:8081/webhook/receiver\"}")
case "$code" in
  201) echo "  등록됨 (max_attempts 기본 12)" ;;
  409) echo "  이미 등록되어 있음" ;;
  *)   echo "  등록 실패 HTTP $code"; exit 1 ;;
esac

say "3) 소비자 응답 드롭률 ${RATE}"
chaos -X POST "${CHAOS_URL}?rate=${RATE}"; echo

say "4) 웹훅 ${N}건 전송 (run=${RUN})"
BODY="{\"orderId\": 123, \"amount\": ${AMOUNT}}"
SIG="sha256=$(printf '%s' "$BODY" | openssl dgst -sha256 -mac HMAC -macopt "key:${SECRET}" -hex | sed 's/^.*= //')"
for i in $(seq 1 "$N"); do
  status=$(curl -s -o /dev/null -w '%{http_code}' -X POST "${GATEWAY}/webhooks/${SLUG}" \
    -H 'Content-Type: application/json' \
    -H "X-GitHub-Delivery: ${RUN}-${i}" \
    -H "X-Hub-Signature-256: ${SIG}" \
    -d "$BODY")
  [ "$status" = "200" ] || { echo "  수신 실패 ${i}번째 HTTP ${status}"; exit 1; }
done
echo "  ${N}건 수신 완료"

say "5) 전달이 끝날 때까지 대기 (최대 ${WAIT_SECONDS}초)"
for _ in $(seq 1 "$WAIT_SECONDS"); do
  inflight=$(q webhook_gateway "SELECT COUNT(*) FROM event
                                 WHERE idempotency_key LIKE '${RUN}-%' AND status IN ('PENDING', 'DELIVERING');")
  [ "$inflight" = "0" ] && break
  sleep 1
done
echo "  남은 PENDING/DELIVERING: ${inflight}"

dropped=$(chaos "${CHAOS_URL}" | grep -o '"dropped":[0-9]*' | cut -d: -f2)

delivered=$(q webhook_gateway "SELECT COUNT(*) FROM event WHERE idempotency_key LIKE '${RUN}-%' AND status = 'DELIVERED';")
dead=$(q webhook_gateway "SELECT COUNT(*) FROM event WHERE idempotency_key LIKE '${RUN}-%' AND status = 'DEAD';")
reached=$(q webhook_gateway "SELECT COUNT(*) FROM delivery_attempt da JOIN event e ON e.id = da.event_id
                              WHERE e.idempotency_key LIKE '${RUN}-%' AND da.failure_class IN ('SUCCESS', 'CONN_RESET');")
resets=$(q webhook_gateway "SELECT COUNT(*) FROM delivery_attempt da JOIN event e ON e.id = da.event_id
                             WHERE e.idempotency_key LIKE '${RUN}-%' AND da.failure_class = 'CONN_RESET';")
max_attempt=$(q webhook_gateway "SELECT COALESCE(MAX(attempt_count), 0) FROM event WHERE idempotency_key LIKE '${RUN}-%';")
balance=$(q webhook_service "SELECT balance FROM account WHERE id = 1;")
processed=$(q webhook_service "SELECT COUNT(*) FROM processed_event;")

payments=$(( (START_BALANCE - balance) / AMOUNT ))
lost=$(( N - delivered ))
dup_delivery=$(( reached - N ))
# 결제가 보낸 건수보다 많으면 중복 처리, 처리 기록보다 적으면 차감이 사라진 것이다. 둘은 다른 결함이다.
dup_processing=$(( payments > N ? payments - N : 0 ))
missing=$(( processed > payments ? processed - payments : 0 ))

say "결과"
printf '  | 시나리오 | 유실 | 중복 전달 | 중복 처리 |\n'
printf '  | --- | --- | --- | --- |\n'
printf '  | 소비자 응답 드롭 %s (%s건) | %s | %s | %s |\n' "$RATE" "$N" "$lost" "$dup_delivery" "$dup_processing"
echo
echo "  대조   — 게이트웨이 CONN_RESET ${resets}건 / 소비자가 센 드롭 ${dropped}건"
echo "  정합성 — 처리 기록 ${processed}건 / 실제 결제 ${payments}회 · DEAD ${dead}건 · 한 이벤트 최대 시도 ${max_attempt}회"

fail=0
if [ "$missing" -ne 0 ]; then
  printf '\n\033[31m처리 기록은 %s건인데 결제는 %s회 — 동시 결제에서 차감 %s건이 사라졌다 (lost update)\033[0m\n' \
    "$processed" "$payments" "$missing"
  fail=1
fi
if [ "$lost" -ne 0 ] || [ "$dup_processing" -ne 0 ]; then
  printf '\n\033[31m기대값과 다르다 — 유실 %s · 중복 처리 %s\033[0m\n' "$lost" "$dup_processing"
  fail=1
fi
[ "$fail" -eq 0 ] || exit 1
printf '\n\033[32m유실 0 · 중복 처리 0. 중복 전달 %s건은 게이트웨이가 막을 수 없고, 소비자 멱등이 막았다.\033[0m\n' "$dup_delivery"
