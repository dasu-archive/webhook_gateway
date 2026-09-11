#!/usr/bin/env bash
# 게이트웨이 → 소비자 서비스 전 구간을 한 번 흘려보고 결과를 확인한다.
#
#   docker compose up -d --build
#   000_Infra/tools/e2e.sh
#
# 하는 일
#   1. 소비자 DB 에 계좌(잔액 5000)와 주문(1000원)을 심는다
#   2. 게이트웨이에 엔드포인트를 등록한다 (targetUrl 은 컨테이너 이름으로 해석된다)
#   3. 제공자인 척 서명을 붙여 웹훅을 쏜다
#   4. 게이트웨이 원장과 소비자 잔액을 확인한다

set -euo pipefail

GATEWAY="${GATEWAY:-http://localhost:8080}"
SLUG="${SLUG:-svc-payment}"
SECRET="${SECRET:-topsecret-secret-1234}"
ORDER_ID="${ORDER_ID:-123}"
AMOUNT="${AMOUNT:-1000}"
MYSQL="docker exec -i -e MYSQL_PWD=root wg-mysql mysql -uroot"

say() { printf '\n\033[1m%s\033[0m\n' "$*"; }

say "1) 소비자 DB 시드 — 계좌 1번 잔액 5000, 주문 ${ORDER_ID}번 ${AMOUNT}원"
$MYSQL webhook_service <<SQL
INSERT INTO account (id, balance) VALUES (1, 5000)
  ON DUPLICATE KEY UPDATE balance = 5000;
INSERT INTO orders (id, account_id, amount) VALUES (${ORDER_ID}, 1, ${AMOUNT})
  ON DUPLICATE KEY UPDATE amount = ${AMOUNT};
DELETE FROM processed_event;
SQL
$MYSQL -N webhook_service -e "SELECT CONCAT('  시드 후 잔액: ', balance) FROM account WHERE id = 1;"

say "2) 엔드포인트 등록 — ${SLUG} → http://service:8081/webhook/receiver"
code=$(curl -sS -o /tmp/ep.json -w '%{http_code}' -X POST "${GATEWAY}/admin/endpoints" \
  -H 'Content-Type: application/json' \
  -d "{\"slug\":\"${SLUG}\",\"provider\":\"github\",\"secret\":\"${SECRET}\",\"targetUrl\":\"http://service:8081/webhook/receiver\"}")
case "$code" in
  201) echo "  등록됨" ;;
  409) echo "  이미 등록되어 있음 (그대로 진행)" ;;
  *)   echo "  등록 실패 HTTP $code"; cat /tmp/ep.json; exit 1 ;;
esac

say "3) 웹훅 전송 — 제공자 서명을 붙여 POST"
BODY="{\"orderId\": ${ORDER_ID}, \"amount\": ${AMOUNT}}"
SIG="sha256=$(printf '%s' "$BODY" | openssl dgst -sha256 -mac HMAC -macopt "key:${SECRET}" -hex | sed 's/^.*= //')"
DELIVERY="e2e-$(date +%s)"
curl -sS -X POST "${GATEWAY}/webhooks/${SLUG}" \
  -H 'Content-Type: application/json' \
  -H "X-GitHub-Delivery: ${DELIVERY}" \
  -H "X-Hub-Signature-256: ${SIG}" \
  -d "$BODY"
echo

say "4) 전달 대기 — 디스패처 폴링 주기가 500ms 다"
for i in $(seq 1 20); do
  status=$($MYSQL -N webhook_gateway -e \
    "SELECT status FROM event WHERE idempotency_key = '${DELIVERY}' LIMIT 1;" 2>/dev/null | tr -d '\r')
  [ "$status" = "DELIVERED" ] && break
  sleep 0.5
done

say "결과"
echo "  게이트웨이 원장:"
$MYSQL -t webhook_gateway -e \
  "SELECT id, status, attempt_count FROM event WHERE idempotency_key = '${DELIVERY}';"
echo "  전달 시도:"
$MYSQL -t webhook_gateway -e \
  "SELECT event_id, attempt_no, response_status, outcome, failure_class, backoff_ms, duration_ms FROM delivery_attempt ORDER BY id DESC LIMIT 3;"
echo "  소비자 잔액과 처리 기록:"
$MYSQL -t webhook_service -e "SELECT id, balance FROM account WHERE id = 1;"
$MYSQL -t webhook_service -e "SELECT gateway_event_id, order_id FROM processed_event;"

if [ "${status:-}" = "DELIVERED" ]; then
  printf '\n\033[32m전 구간 통과 — 잔액이 4000 이면 성공입니다.\033[0m\n'
else
  printf '\n\033[31m전달이 완료되지 않았습니다. docker compose logs gateway 를 확인하세요.\033[0m\n'
  exit 1
fi
