# 웹훅 게이트웨이 — 통합 스택

외부 제공자가 보내는 웹훅을 애플리케이션 대신 받아 **원본 바이트 그대로** 저장한 뒤,
소비자가 처리에 성공할 때까지 책임지고 전달하는 게이트웨이와, 그 전달을 받는
소비자 서비스를 한 저장소에서 함께 돌린다.

```
제공자 ──POST──▶ 게이트웨이(8080) ──POST──▶ 소비자 서비스(8081)
                 수신부 → 원장 → 전달부              결제 차감 + 멱등 처리
                 (즉시 200)  (MySQL)  (재시도 + 데드레터)
```

## 구조

| 경로 | 내용 |
| --- | --- |
| [`000_Infra/`](000_Infra) | 배포 단위가 아닌 공통 자산 — DB 초기화 스크립트, 검증 도구 |
| [`001_WebhookGateway/`](001_WebhookGateway) | 게이트웨이 본체. [README](001_WebhookGateway/README.md) · [설계](001_WebhookGateway/webhook-gateway-design.md) |
| [`002_WebhookService/`](002_WebhookService) | 웹훅을 받아 처리하는 소비자 서비스 |
| [`docs/`](docs) | 차수별 구현 기록 · 검증 결과 · 남은 과제 |
| `compose.yaml` | 전체 스택. 상대경로 기준이 루트라 여기서 실행한다 |

번호 접두는 **배포 단위**에만 붙인다. `000_` 은 서비스가 아닌 공통 자산이라는 표시다.

## 빠른 시작

```bash
docker compose up -d --build      # 게이트웨이 + 소비자 + MySQL
000_Infra/tools/e2e.sh            # 등록 → 발사 → 원장·잔액까지 한 번에 확인
```

`e2e.sh` 는 소비자 계좌에 5000원을 심고 1000원짜리 주문 웹훅을 흘린 뒤, 잔액이 4000이 되고
게이트웨이 원장이 `DELIVERED` 인지까지 확인한다.

```bash
docker compose down               # 정지 (데이터 유지)
docker compose down -v            # 정지 + 볼륨 삭제
```

MySQL 만 띄우고 게이트웨이는 IDE 에서 돌리려면 `docker compose up -d mysql` 뒤
[게이트웨이 README](001_WebhookGateway/README.md) 의 실행 절을 따른다.

## 문서

| 차수 | 문서 |
| --- | --- |
| 1차 MVP | [구현 기록](docs/2026-09-04_01_mvp_구현기록.md) · [검증 결과](docs/2026-09-04_01_mvp_검증결과.md) · [남은 과제](docs/2026-09-04_01_mvp_남은과제.md) |
| 2차 통합 | [구현 기록](docs/2026-09-09_02_통합_구현기록.md) · [검증 결과](docs/2026-09-09_02_통합_검증결과.md) · [남은 과제](docs/2026-09-09_02_통합_남은과제.md) |
