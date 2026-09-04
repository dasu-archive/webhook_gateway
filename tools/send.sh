#!/usr/bin/env bash
# 제공자 흉내. 설계 11.1 — "서명 붙여 POST 쏘는 셸 스크립트".
#
# 사용법
#   tools/send.sh <slug> <secret> [github|standard] [body-file] [count]
#
# 예시
#   tools/send.sh gh-push topsecret-secret-1234 github                    # 1건
#   tools/send.sh gh-push topsecret-secret-1234 github payload.json 100   # 100건
#   tools/send.sh gh-push topsecret-secret-1234 github - 1 --same-id      # 같은 ID 로 중복 전송
#
# --same-id 는 멱등키 중복 차단을 검증할 때 쓴다. 두 번째부터는 200 duplicate 가 나와야 한다.

set -euo pipefail

GATEWAY="${GATEWAY:-http://localhost:8080}"

slug="${1:?slug 필요}"
secret="${2:?secret 필요}"
provider="${3:-github}"
body_file="${4:--}"
count="${5:-1}"
same_id=false
for arg in "$@"; do
  [[ "$arg" == "--same-id" ]] && same_id=true
done

tmp_body="$(mktemp)"
trap 'rm -f "$tmp_body"' EXIT

if [[ "$body_file" == "-" ]]; then
  # 공백을 일부러 넣어 두었다. 재직렬화하면 사라지는 바이트라 원본 보존 여부가 드러난다.
  printf '{"orderId": 123, "status": "DONE"}' > "$tmp_body"
else
  cp "$body_file" "$tmp_body"
fi

hmac_hex() { openssl dgst -sha256 -mac HMAC -macopt "key:$1" -hex < "$2" | sed 's/^.*= //'; }
hmac_b64() { openssl dgst -sha256 -mac HMAC -macopt "key:$1" -binary < "$2" | openssl base64 -A; }
uuid() { cat /proc/sys/kernel/random/uuid 2>/dev/null || openssl rand -hex 16; }

fixed_id="$(uuid)"
ok=0; dup=0; other=0

for ((i = 1; i <= count; i++)); do
  if [[ "$same_id" == true ]]; then event_id="$fixed_id"; else event_id="$(uuid)"; fi

  case "$provider" in
    github)
      sig="sha256=$(hmac_hex "$secret" "$tmp_body")"
      status=$(curl -s -o /tmp/send_resp.$$ -w '%{http_code}' -X POST "$GATEWAY/webhooks/$slug" \
        -H 'Content-Type: application/json' \
        -H "X-GitHub-Event: push" \
        -H "X-GitHub-Delivery: $event_id" \
        -H "X-Hub-Signature-256: $sig" \
        --data-binary "@$tmp_body")
      ;;
    standard)
      ts="$(date +%s)"
      signed="$(mktemp)"
      { printf '%s.%s.' "$event_id" "$ts"; cat "$tmp_body"; } > "$signed"
      sig="v1,$(hmac_b64 "$secret" "$signed")"
      rm -f "$signed"
      status=$(curl -s -o /tmp/send_resp.$$ -w '%{http_code}' -X POST "$GATEWAY/webhooks/$slug" \
        -H 'Content-Type: application/json' \
        -H "webhook-id: $event_id" \
        -H "webhook-timestamp: $ts" \
        -H "webhook-signature: $sig" \
        --data-binary "@$tmp_body")
      ;;
    *)
      echo "알 수 없는 provider: $provider (github | standard)" >&2
      exit 2
      ;;
  esac

  resp="$(cat /tmp/send_resp.$$ 2>/dev/null || true)"
  rm -f /tmp/send_resp.$$

  if [[ "$status" == "200" ]]; then
    if [[ "$resp" == *duplicate* ]]; then dup=$((dup + 1)); else ok=$((ok + 1)); fi
  else
    other=$((other + 1))
    echo "[$i] HTTP $status $resp" >&2
  fi

  if (( count <= 5 )); then echo "[$i] HTTP $status $resp"; fi
done

echo "완료: accepted=$ok duplicate=$dup 기타=$other (총 $count)"
