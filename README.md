# 웹훅 수신 게이트웨이 — MVP

외부 서비스가 보내는 웹훅을 애플리케이션 대신 받아, **원본 바이트 그대로** 저장한 뒤,
소비자가 처리에 성공할 때까지 책임지고 전달한다. 페이로드는 해석하지 않는다.

| 문서 | 내용 |
| --- | --- |
| [설계](webhook-gateway-design.md) | 문제 정의부터 v1 범위까지. 이 프로젝트의 기준 문서 |
| [1차 구현 기록](docs/2026-09-04_01_mvp_구현기록.md) | MVP 로 무엇을 만들었나. 설계와 다르게 간 지점과 그 근거 |
| [1차 검증 결과](docs/2026-09-04_01_mvp_검증결과.md) | 테스트 31개와 실측 시나리오. 아직 측정 못 한 것 |
| [1차 남은 과제](docs/2026-09-04_01_mvp_남은과제.md) | v1 까지 남은 것과 우선순위 |

```
제공자 ──POST──▶ 게이트웨이 ──POST──▶ 소비자
                 수신부 → 원장 → 전달부
                 (즉시 200)  (MySQL)  (재시도 + 데드레터)
```

---

## MVP 에 들어간 것

| 영역 | 내용 | 설계 |
| --- | --- | --- |
| 수신부 | `byte[]` 수신, HMAC-SHA256 검증, 상수 시간 비교, 시크릿 로테이션 유예, 타임스탬프 윈도우 | 6절 |
| 제공자 | `github`(서명만), `standard`(타임스탬프 포함) 2종 | 6.3 |
| 원장 | `endpoint` / `event` / `delivery_attempt`, 유니크 인덱스 기반 중복 차단 | 7절 |
| 전달부 | `FOR UPDATE SKIP LOCKED` 폴링, 지수 백오프 + 지터 3종, 데드레터 | 8절, 10.1 |
| 좀비 회수 | `DELIVERING` 인 채 방치된 행을 `PENDING` 으로 되돌린다 | 7.2.1 |
| 관리 평면 | 엔드포인트 등록·시크릿 교체·원장 조회 | 5절 |
| 검증 도구 | 목 소비자, 발신 스크립트 | 11.1 |

## MVP 에서 뺀 것 (v1 예정)

| 항목 | 왜 뺐나 |
| --- | --- |
| 재생 (9절) | `replay_of` 컬럼과 `X-Gateway-Replay` 헤더는 이미 있다. 범위 지정·드라이런·재생 이력이 남았다 |
| `signature_mode: resign` | 재생과 한 묶음이다. 지금은 `PASSTHROUGH` 만 |
| 서킷 브레이커 (8.3) | 재시도·백오프가 먼저 돌아야 임계값을 정할 수 있다 |
| 멱등키 정리 배치 (7.5) | `retention_days` 컬럼만 있고 삭제 배치는 없다. **v1 에 반드시 넣어야 한다** |
| 부하 측정 (11.2) | k6 스크립트 미작성. 설계 10.1의 전환 기준이 아직 미측정으로 남아 있다 |
| 큰 페이로드 분리 (13절) | 1MB 초과는 413 으로 거부한다. 오브젝트 스토리지 분리는 미구현 |

설계 12.2의 의도적 제외 항목(순서 보장, 팬아웃, 페이로드 변환, 멀티테넌시)은 v1 에서도 넣지 않는다.

---

## 실행

### 1. MySQL

```bash
docker compose up -d
```

MySQL 8.0 을 `localhost:3307` 에 띄운다. `SKIP LOCKED` 가 8.0 전용이라 5.7 로는 못 돌린다.
스키마는 애플리케이션 기동 시 Flyway 가 만든다.

### 2. 게이트웨이

```bash
./gradlew bootRun
```

### 3. 목 소비자

```bash
java tools/MockConsumer.java 9090
```

### 4. 엔드포인트 등록

```bash
curl -X POST localhost:8080/admin/endpoints \
  -H 'Content-Type: application/json' \
  -d '{
        "slug": "gh-push",
        "provider": "github",
        "secret": "topsecret-secret-1234",
        "targetUrl": "http://localhost:9090/consume",
        "maxAttempts": 8
      }'
```

### 5. 웹훅 발사

```bash
tools/send.sh gh-push topsecret-secret-1234 github
```

목 소비자 콘솔에 `event=1 attempt=1 seen=1 -> 200` 이 찍히고,
`curl localhost:8080/admin/events` 의 상태가 `DELIVERED` 가 된다.

---

## 검증 시나리오

설계 11.1의 목 소비자 제어 평면을 쓴다.

### 재시도가 실제로 먹히는가

```bash
# 처음 3회는 503, 그 다음부터 200
curl -X POST 'localhost:9090/_control/mode?mode=FAIL_THEN_OK&failFirst=3&status=503'
tools/send.sh gh-push topsecret-secret-1234 github

# 백오프가 1s → 2s → 4s 로 벌어지는 것을 시도 이력에서 확인한다
curl -s localhost:8080/admin/events/1 | jq '.attempts'
```

### 중복이 차단되는가 (설계 7.4)

```bash
# 같은 이벤트 ID 로 20번 쏜다
tools/send.sh gh-push topsecret-secret-1234 github - 20 --same-id
# → accepted=1 duplicate=19

# 소비자가 받은 건수도 1이어야 한다
curl -s localhost:9090/_control/state | jq '.duplicates'   # 0
```

### 소비자가 다운됐다 살아나면 (설계 8.2, thundering herd)

```bash
curl -X POST 'localhost:9090/_control/down?seconds=60'
tools/send.sh gh-push topsecret-secret-1234 github - 200

# 60초 뒤 복구. 지터 전략을 바꿔가며 소진 곡선을 비교한다
#   gateway.dispatch.backoff.jitter = NONE | FULL | DECORRELATED
```

`NONE` 으로 두면 재시도 시각이 한 점에 몰리는 것을, `FULL` 로 두면 흩어지는 것을
`delivery_attempt.started_at` 분포에서 볼 수 있다.

### 4xx 는 재시도하지 않는가 (설계 8.1.1)

```bash
curl -X POST 'localhost:9090/_control/mode?mode=FAIL_4XX'
tools/send.sh gh-push topsecret-secret-1234 github
curl -s 'localhost:8080/admin/events?limit=1' | jq '.[0].status'   # DEAD, attemptCount=1
```

---

## 소비자가 지켜야 할 것

설계 15절 그대로다.

1. **`X-Gateway-Event-Id` 로 중복을 차단한다.** 게이트웨이의 중복 차단만 믿으면 안 된다.
   게이트웨이→소비자 구간도 at-least-once 이고, 좀비 회수는 정상 처리된 건을 다시 보낼 수 있다.
2. **도착 순서를 신뢰하지 않는다.** 순서는 보장하지 않는다. 이벤트 시각으로 판정한다.
3. **응답 코드로 의도를 표현한다.** 성공 2xx / 재시도 필요 5xx / 재시도 무의미 4xx.
   "아직 주문이 안 만들어진 것"에 4xx 를 주면 영영 처리되지 않는다.
4. `X-Gateway-Replay: true` 일 때 알림성 부작용을 건너뛸지 결정한다.

전달 시 붙는 헤더:

| 헤더 | 내용 |
| --- | --- |
| `X-Gateway-Event-Id` | 게이트웨이 이벤트 ID. 소비자 멱등키 |
| `X-Gateway-Endpoint` | 엔드포인트 slug |
| `X-Gateway-Attempt` | 몇 번째 시도인가 (1부터) |
| `X-Gateway-Received-At` | 게이트웨이가 받은 시각 (ISO-8601) |
| `X-Gateway-Replay` | 재생 여부 |
| `X-Gateway-Idempotency-Source` | `PROVIDER_ID` / `BODY_HASH` |
| (원본 헤더 전부) | `PASSTHROUGH` 모드이므로 제공자 서명 헤더가 그대로 간다 |

---

## 관리 API

`gateway.admin.token` 을 설정하면 `X-Admin-Token` 헤더를 요구한다. 비어 있으면 인증 없음.

| 메서드 | 경로 | 용도 |
| --- | --- | --- |
| POST | `/admin/endpoints` | 등록 |
| GET | `/admin/endpoints` | 목록 |
| POST | `/admin/endpoints/{slug}/secret` | 시크릿 교체 (구 시크릿은 유예 기간 동안 계속 통과) |
| POST | `/admin/endpoints/{slug}/enabled?value=false` | 수신 중단 |
| GET | `/admin/events?endpoint=&status=&limit=&afterId=` | 원장 조회 |
| GET | `/admin/events/{id}` | 원본 바디(base64)와 시도 이력 |
| GET | `/admin/stats` | 상태별 건수. 데드레터가 쌓이는지 본다 |

---

## 테스트

```bash
./gradlew test
```

- 단위: 서명 검증(바이트 1개 차이, 타임스탬프 윈도우, 로테이션), 백오프/지터
- 통합: MySQL 8.0 Testcontainers. 원본 바이트 보존, 동시 중복 차단, 재시도/데드레터, 좀비 회수

통합 테스트는 Docker 가 필요하다. Docker Engine 29 는 API 1.40 미만 요청을 400 으로 거부하는데
Testcontainers 가 쓰는 docker-java 의 기본 협상 버전이 그보다 낮아서, `build.gradle` 의 test 태스크에서
`api.version` 을 명시한다. 다른 버전이 필요하면 `-Dapi.version=...` 으로 덮어쓴다.

---

## 알려진 한계

설계 14절이 그대로 적용된다. 그중 MVP 에서 특히 유의할 것:

1. **exactly-once 를 제공하지 않는다.** 소비자 멱등성이 전제다.
2. **순서를 보장하지 않는다.** 소비자가 이벤트 시각으로 판정해야 한다.
3. **이벤트 ID 를 안 주는 제공자에게 멱등성은 근사치다.** 바디 해시는 같은 사용자의 정상 중복 액션을
   중복으로 오판할 수 있다. `idempotency_source` 로 어느 방식이었는지 구분해 둔다.
4. **게이트웨이가 새로운 단일 장애점이다.** GitHub 처럼 재시도하지 않는 제공자에게는
   게이트웨이 다운 시간 동안 온 것이 유실된다. FM-2 를 풀려고 만든 물건이 같은 문제를 자기 자신에게 갖는다.
5. **멱등키 정리 배치가 없다.** 유니크 인덱스가 무한히 커진다. v1 의 최우선 과제다.
6. **시크릿이 평문으로 저장된다.** `VARBINARY` 에 원문 바이트가 들어간다. 운영 전 암호화가 필요하다.
7. **DB 큐 처리량 상한이 미측정이다.** 설계 10.1의 전환 기준(폴링 p99 100ms)은 아직 숫자가 없다.

### 설계 문서와 다르게 구현한 것

설계 8.1.1은 `4xx → 즉시 DEAD` 로 규정한다. 여기서는 **408 과 429 를 예외로 두고 재시도한다.**
둘은 "영원히 안 된다"가 아니라 "지금은 안 된다"는 뜻이고, 소비자가 스로틀링만 해도
이벤트를 잃는 것은 게이트웨이가 존재하는 이유와 정면으로 어긋나기 때문이다.
