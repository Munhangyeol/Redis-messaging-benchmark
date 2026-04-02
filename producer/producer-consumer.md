# Producer-Consumer 연동 가이드

## 전체 구조

```
Python 스크립트
  │
  ├─ POST /consumer:8081/{pattern}/start   ← 컨슈머 시작
  ├─ POST /producer:8080/{pattern}/send × N회  ← 메시지 전송
  ├─ POST /consumer:8081/{pattern}/stop    ← 컨슈머 종료 + 결과 반환
  └─ GET  /consumer:8081/{pattern}/results ← 저장된 결과 조회
```

- **Consumer 포트**: `8081`
- **Producer 포트**: `8080` (권장)

---

## Redis 키 규칙

| 패턴 | Redis 키 | 비고 |
|---|---|---|
| Queue | `redis:queue` | LIST 자료구조 |
| Stream XREAD | `redis:stream:xread` | Stream 자료구조 |
| Stream Group | `redis:stream:group` | Stream 자료구조 |
| Pub/Sub | `redis:pubsub` | 채널명 |

---

## 패턴별 Producer 구현 명세

### 1. Queue

- **Redis 명령**: `LPUSH redis:queue {message}`
- **Producer API**: `POST /queue/send`
- **Consumer 동작**: `BRPOP redis:queue 1` (블로킹 폴링)

```
Producer → LPUSH redis:queue "message-1"
Consumer → BRPOP redis:queue (자동 수신)
```

**Spring 구현 (`StringRedisTemplate` 사용)**
```java
// QueueProducer.java
redisTemplate.opsForList().leftPush("redis:queue", message);
```

---

### 2. Stream XREAD

- **Redis 명령**: `XADD redis:stream:xread * payload {message}`
- **Producer API**: `POST /stream-xread/send`
- **Consumer 동작**: `XREAD COUNT 100 BLOCK 1000 STREAMS redis:stream:xread {lastId}`

```
Producer → XADD redis:stream:xread * payload "message-1"
Consumer → XREAD ... (오프셋 기반으로 순차 읽기)
```

**Spring 구현 (`StringRedisTemplate` 사용)**
```java
// StreamXReadProducer.java
redisTemplate.opsForStream().add("redis:stream:xread", Map.of("payload", message));
```

메시지 필드명: `payload`

---

### 3. Stream Group

- **Redis 명령**: `XADD redis:stream:group * payload {message}`
- **Producer API**: `POST /stream-group/send`
- **Consumer 동작**: `XREADGROUP GROUP benchmark-group consumer-1 COUNT 100 BLOCK 1000 STREAMS redis:stream:group >`
- **Consumer 그룹명**: `benchmark-group`
- **ACK**: Consumer가 메시지 처리 후 자동으로 `XACK` 처리함

```
Producer → XADD redis:stream:group * payload "message-1"
Consumer → XREADGROUP ... + XACK (자동 처리)
```

**Spring 구현 (`StringRedisTemplate` 사용)**
```java
// StreamGroupProducer.java
redisTemplate.opsForStream().add("redis:stream:group", Map.of("payload", message));
```

메시지 필드명: `payload` (Stream XREAD와 동일)

> **주의**: Consumer의 `/stream-group/start` 호출 시 그룹이 자동 생성됨 (MKSTREAM 포함).
> Producer는 그룹 생성 불필요.

---

### 4. Pub/Sub

- **Redis 명령**: `PUBLISH redis:pubsub {message}`
- **Producer API**: `POST /pubsub/send`
- **Consumer 동작**: `SUBSCRIBE redis:pubsub` (push 방식, 폴링 없음)

```
Producer → PUBLISH redis:pubsub "message-1"
Consumer → onMessage() 콜백 자동 호출
```

**Spring 구현 (`StringRedisTemplate` 사용)**
```java
// PubSubProducer.java
redisTemplate.convertAndSend("redis:pubsub", message);
```

> **주의**: Pub/Sub은 Consumer가 구독 중일 때 발행된 메시지만 수신됨.
> 반드시 Consumer `/start` 후에 Producer가 메시지를 전송해야 함.

---

## Producer 패키지 구조 (권장)

Consumer와 동일한 패키지 구조로 구성:

```
com.example.producer
├── queue/
│   ├── QueueProducer.java       # leftPush 로직
│   └── QueueController.java     # POST /queue/send
├── streamxread/
│   ├── StreamXReadProducer.java # opsForStream().add()
│   └── StreamXReadController.java
├── streamgroup/
│   ├── StreamGroupProducer.java # opsForStream().add() (키만 다름)
│   └── StreamGroupController.java
└── pubsub/
    ├── PubSubProducer.java      # convertAndSend()
    └── PubSubController.java
```

각 컨트롤러의 `/send` 엔드포인트는 요청 body나 파라미터로 메시지를 받아 Redis로 전송.

## application.properties (Producer)

```properties
spring.application.name=producer
server.port=8080

spring.data.redis.host=localhost
spring.data.redis.port=6379
```

---

## Consumer API 전체 목록

| 메서드 | 경로 | 설명 |
|---|---|---|
| POST | `/queue/start` | Queue 컨슈머 시작 |
| POST | `/queue/stop` | Queue 컨슈머 중지 + 결과 반환 |
| GET | `/queue/stats` | Queue 실시간 통계 |
| GET | `/queue/results` | Queue 저장된 벤치마크 결과 목록 |
| POST | `/stream-xread/start` | Stream XREAD 컨슈머 시작 |
| POST | `/stream-xread/stop` | Stream XREAD 컨슈머 중지 + 결과 반환 |
| GET | `/stream-xread/stats` | Stream XREAD 실시간 통계 |
| GET | `/stream-xread/results` | Stream XREAD 저장된 결과 목록 |
| POST | `/stream-group/start` | Stream Group 컨슈머 시작 |
| POST | `/stream-group/stop` | Stream Group 컨슈머 중지 + 결과 반환 |
| GET | `/stream-group/stats` | Stream Group 실시간 통계 |
| GET | `/stream-group/results` | Stream Group 저장된 결과 목록 |
| POST | `/pubsub/start` | Pub/Sub 구독 시작 |
| POST | `/pubsub/stop` | Pub/Sub 구독 중지 + 결과 반환 |
| GET | `/pubsub/stats` | Pub/Sub 실시간 통계 |
| GET | `/pubsub/results` | Pub/Sub 저장된 결과 목록 |

### `/stop` 응답 예시

```json
{
  "pattern": "QUEUE",
  "running": false,
  "totalMessages": 100000,
  "durationMs": 4823,
  "throughput": "20733.47 msg/sec"
}
```

---

## Producer 구현 시 주의사항

1. **포트 충돌 방지**: Consumer가 `8081`을 사용하므로 Producer는 `8080` 사용
2. **Stream vs Queue 메시지 형식**:
   - Queue / Pub/Sub: 평문 문자열 (`"message-1"`)
   - Stream XREAD / Group: 해시맵 (`{"payload": "message-1"}`)
3. **Pub/Sub 순서**: Consumer `/start` → Producer 전송 순서 필수
4. **Stream Group 순서**: Consumer `/start`가 그룹을 생성하므로 반드시 Consumer 먼저 시작
