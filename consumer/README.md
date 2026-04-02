# Redis 메시징 패턴 성능 비교

Redis의 4가지 메시징 패턴 처리량(msg/sec)을 비교하는 벤치마크 프로젝트.

| 패턴 | Redis 명령 |
|---|---|
| Queue | `LPUSH` / `BRPOP` |
| Stream XREAD | `XADD` / `XREAD BLOCK` |
| Stream Group | `XADD` / `XREADGROUP` + `XACK` |
| Pub/Sub | `PUBLISH` / `SUBSCRIBE` |

---

## 프로젝트 구조

```
redis/
├── consumer/   # 이 프로젝트 - 메시지 수신 (포트 8081)
├── producer/   # 별도 프로젝트 - 메시지 발행 (포트 8080)
└── producer-consumer.md  # 연동 명세
```
> Consumer와 Producer는 **반드시 별도 프로세스**로 실행해야 합니다.
---

## 실행 방법

### 사전 조건

- Java 17+
- Redis 서버 실행 중 (`localhost:6379`)

### Consumer 실행

```bash
cd consumer
./gradlew bootRun
# → http://localhost:8081
```

### Producer 실행

```bash
cd producer
./gradlew bootRun
# → http://localhost:8080
```

---

## 벤치마크 실행 순서

각 패턴별로 아래 순서를 반드시 지켜야 합니다.

```
1. Consumer /start  →  2. Producer /send × N  →  3. Consumer /stop
```

### 수동 실행 예시 (Queue)

```bash
# 1. Consumer 시작
curl -X POST http://localhost:8081/queue/start

# 2. Producer로 메시지 전송 (Python 또는 직접 호출)
curl -X POST http://localhost:8080/queue/send -d "message-1"

# 3. Consumer 중지 + 결과 확인
curl -X POST http://localhost:8081/queue/stop
```

### Python 부하 테스트 스크립트 예시

```python
import requests

BASE_CONSUMER = "http://localhost:8081"
BASE_PRODUCER = "http://localhost:8080"
PATTERN = "queue"   # queue | stream-xread | stream-group | pubsub
N = 100_000

# 1. Consumer 시작
requests.post(f"{BASE_CONSUMER}/{PATTERN}/start")

# 2. 메시지 N회 전송
for i in range(N):
    requests.post(f"{BASE_PRODUCER}/{PATTERN}/send", data=f"message-{i}")

# 3. Consumer 중지 및 결과 출력
result = requests.post(f"{BASE_CONSUMER}/{PATTERN}/stop").json()
print(result)
```

---

## API 요약

### Consumer (`:8081`)

| 메서드 | 경로 | 설명 |
|---|---|---|
| POST | `/{pattern}/start` | 컨슈머 시작 |
| POST | `/{pattern}/stop` | 컨슈머 중지 + 최종 결과 반환 |
| GET | `/{pattern}/stats` | 실시간 통계 조회 |
| GET | `/{pattern}/results` | DB에 저장된 과거 결과 목록 |

`{pattern}` = `queue` \| `stream-xread` \| `stream-group` \| `pubsub`

### Producer (`:8080`)

| 메서드 | 경로 | 설명 |
|---|---|---|
| POST | `/{pattern}/send` | 메시지 1건 발행 |

### 결과 응답 예시

```json
{
  "pattern": "QUEUE",
  "running": false,
  "totalMessages": 100000,
  "durationMs": 4823,
  "throughput": "20733.47 msg/sec"
}
```

과거 결과는 H2 인메모리 DB에 저장되며 `GET /{pattern}/results`로 조회합니다.
H2 콘솔: `http://localhost:8081/h2-console` (JDBC URL: `jdbc:h2:mem:benchmarkdb`)

---

## 주의사항

- **Pub/Sub**: Consumer가 구독 중일 때 발행된 메시지만 수신됩니다. Consumer `/start` 전에 발행된 메시지는 유실됩니다.
- **Stream Group**: Consumer `/start` 시 그룹이 자동 생성됩니다. Producer는 그룹 생성이 필요 없습니다.
- **재시작**: H2는 인메모리 DB이므로 애플리케이션 재시작 시 저장된 결과가 초기화됩니다.
