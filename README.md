# Redis 메시징 패턴 벤치마크

4가지 Redis 메시징 패턴의 처리량(msg/sec)을 비교하는 벤치마크 프로젝트임.

| 패턴 | Redis 명령어 | Redis 키 |
|---|---|---|
| Queue | `LPUSH` / `BRPOP` | `redis:queue` |
| Stream XREAD | `XADD` / `XREAD BLOCK` | `redis:stream:xread` |
| Stream Group | `XADD` / `XREADGROUP` + `XACK` | `redis:stream:group` |
| Pub/Sub | `PUBLISH` / `SUBSCRIBE` | `redis:pubsub` |

---

## 프로젝트 구조

```
redis/
├── producer/               # 메시지 발행 서버 (Spring Boot, 포트 8080)
├── consumer/               # 메시지 구독 서버 (Spring Boot, 포트 8081)
├── docker-compose.yml      # Redis 컨테이너 설정
└── compare_benchmark.py    # 4가지 패턴 자동 벤치마크 및 비교 스크립트
```

> Producer와 Consumer는 **별도 프로세스로 실행**해야 함.

---

## 기술 스택

| 모듈 | 버전 |
|---|---|
| Java | 17 |
| Spring Boot (Producer) | 4.0.5 |
| Spring Boot (Consumer) | 3.4.5 |
| Spring Data Redis | - |
| MySQL | 8.x (Consumer 벤치마크 결과 저장) |
| Redis | latest (Docker) |

---

## 사전 요구사항

- Java 17+
- Docker (Redis 컨테이너용)
- MySQL (`localhost:3306` 실행 중, 데이터베이스명: `benchmark`)
- Python 3.x + `requests` 라이브러리 (벤치마크 스크립트용)

---

## 시작하기

### 1. Redis 시작

```bash
docker-compose up -d
# → Redis: localhost:6379
```

### 2. Producer 시작

```bash
cd producer/producer
./gradlew bootRun
# → http://localhost:8080
```

### 3. Consumer 시작

```bash
cd consumer/consumer
./gradlew bootRun
# → http://localhost:8081
```

---

## 벤치마크 실행

### Python 자동 스크립트 (권장)

Producer와 Consumer 서버가 모두 실행 중인 상태에서, 루트 디렉토리에서 `compare_benchmark.py`를 실행.

```bash
pip install requests   # 최초 1회만

python compare_benchmark.py
```

4가지 패턴을 순서대로 실행함 (`queue` → `stream-xread` → `stream-group` → `pubsub`). 각 패턴마다:

1. Consumer `/start` 호출
2. N개의 메시지 전송 (기본값 `N = 1000`, 스크립트에서 변경 가능)
3. Consumer `/stop` 호출 후 결과 출력

출력 예시:

```
[QUEUE] Start (1,000 messages)
  Consumer started
  Sending... 1,000/1,000
  Producer done: 2.34s (427 msg/sec)
  Consumer result: {'pattern': 'QUEUE', 'totalMessages': 1000, 'durationMs': 2100, 'throughput': '476.19 msg/sec'}

========== Final Results ==========
  queue           476.19 msg/sec            (total 1,000 / 2100ms)
  stream-xread    390.12 msg/sec            (total 1,000 / 2563ms)
  stream-group    381.45 msg/sec            (total 1,000 / 2621ms)
  pubsub          512.33 msg/sec            (total 1,000 / 1952ms)
```

> **참고**: 위 수치는 예시이므로 실제 결과는 실행 환경에 따라 다를 수 있음.

### 수동 실행

각 패턴마다 아래 순서를 반드시 따라야 함.

```
1. Consumer /start  →  2. Producer /send × N  →  3. Consumer /stop
```

```bash
# 1. Consumer 시작
curl -X POST http://localhost:8081/queue/start

# 2. Producer로 메시지 전송
curl -X POST http://localhost:8080/queue/send -d "message-1"

# 3. Consumer 종료 및 결과 확인
curl -X POST http://localhost:8081/queue/stop
```

> **Pub/Sub 주의사항**: Consumer가 구독 중인 동안 발행된 메시지만 수신됨. `/start` 이전에 전송된 메시지는 유실되므로, 반드시 Consumer를 먼저 시작할 것.

---

## 패턴별 동작 원리

### Queue

1. `http://localhost:8081/queue/start` 호출을 통해 consumer 프로세스 내부에서 `queue-consumer`라는 이름의 데몬 쓰레드를 생성하고 실행.
2. `http://localhost:8080/queue/send` API를 통해 producer 프로세스가 Redis List(`redis:queue`)에 `LPUSH` 명령으로 메시지를 전송.
3. consumer 쓰레드는 반복문 안에서 1초 timeout의 blocking BRPOP 방식으로 Redis queue에서 메시지를 대기.
4. 메시지가 존재하면 이를 소비하고, DB I/O 저장 로직을 수행한 후 messageCount를 증가시킴.
5. producer가 모든 메시지 전송을 마치면 `http://localhost:8081/queue/stop` API를 호출함. stop API는 consumer 종료 플래그를 내리고, join으로 consumer 쓰레드가 종료될 때까지 최대 30초 대기한 뒤 최종 결과 값을 반환.

<img width="2271" height="1470" alt="mermaid-diagram" src="https://github.com/user-attachments/assets/33b5ad29-b074-44c1-89d8-06321bf315c5" />


### Stream XREAD

1. `http://localhost:8081/stream-xread/start` 호출을 통해 consumer 프로세스 내부에서 `stream-xread-consumer`라는 이름의 데몬 쓰레드를 생성하고 실행. 이 시점에 readOffset을 `$`(latest)로 초기화하여 start 이후에 들어오는 신규 메시지만 읽도록 설정.
2. `http://localhost:8080/stream-xread/send` API를 통해 producer 프로세스가 Redis Stream(`redis:stream:xread`)에 `XADD` 명령으로 메시지를 전송.
3. consumer 쓰레드는 반복문 안에서 1초 timeout의 blocking XREAD 방식으로 Redis Stream에서 최대 100건씩 메시지를 대기.
4. 메시지가 존재하면 수신된 records를 리스트로 변환하여 `saveAll()`로 일괄 DB I/O 저장한 후 messageCount를 일괄 증가시키고, 다음 읽기를 위해 readOffset을 마지막 수신 메시지 ID로 갱신.
5. producer가 모든 메시지 전송을 마치면 `http://localhost:8081/stream-xread/stop` API를 호출함. stop API는 consumer 종료 플래그를 내리고, join으로 consumer 쓰레드가 종료될 때까지 최대 30초 대기함. 쓰레드 종료 전 남은 스트림 메시지를 non-blocking으로 드레인하여 DB 저장한 뒤 최종 결과 값을 반환.

<img width="2168" height="1582" alt="mermaid-diagram (1)" src="https://github.com/user-attachments/assets/4643fe05-1d9d-4afd-9f3d-f1804608f9d7" />


### Stream Group

1. `http://localhost:8081/stream-group/start` 호출을 통해, 기존 컨슈머 그룹을 삭제 후 `XGROUP CREATE ... $ MKSTREAM`으로 재생성(PEL 오염 방지)하고, `stream-group-consumer`라는 이름의 데몬 쓰레드를 생성하고 실행.
2. `http://localhost:8080/stream-group/send` API를 통해 producer 프로세스가 Redis Stream(`redis:stream:group`)에 `XADD` 명령으로 메시지를 전송.
3. consumer 쓰레드는 반복문 안에서 1초 timeout의 blocking XREADGROUP 방식으로 ReadOffset `>`(미전달 신규 메시지)를 기준으로 Redis Stream에서 최대 100건씩 메시지를 대기.
4. 메시지가 존재하면 수신된 records를 리스트로 변환하여 `saveAll()`로 일괄 DB I/O 저장한 후 messageCount를 일괄 증가시키고, 이후 `XACK` 명령으로 처리 완료를 그룹에 알림.
5. producer가 모든 메시지 전송을 마치면 `http://localhost:8081/stream-group/stop` API를 호출함. stop API는 consumer 종료 플래그를 내리고, join으로 consumer 쓰레드가 종료될 때까지 최대 30초 대기함. 쓰레드 종료 전 남은 스트림 메시지를 non-blocking으로 드레인하여 DB 저장 및 ACK한 뒤 최종 결과 값을 반환.

<img width="2035" height="1694" alt="mermaid-diagram (3)" src="https://github.com/user-attachments/assets/de1948c9-cb8a-4c5f-9b52-20d4724a8857" />

### Pub/Sub

1. `http://localhost:8081/pubsub/start` 호출을 통해 Spring의 `RedisMessageListenerContainer`에 메시지 리스너를 등록. 별도의 데몬 쓰레드를 직접 생성하지 않으며, 컨테이너 내부 쓰레드 풀이 구독을 관리.
2. `http://localhost:8080/pubsub/send` API를 통해 producer 프로세스가 Redis Pub/Sub 채널(`redis:pubsub`)에 `PUBLISH` 명령으로 메시지를 전송.
3. consumer는 이벤트 드리븐 방식으로 동작함. 메시지가 채널에 발행되면 `onMessage()` 콜백이 즉시 호출됨.
4. 콜백 내에서 메시지를 DB I/O 저장한 후 messageCount를 증가시킴. Pub/Sub 특성상 메시지는 영속되지 않으며 subscriber가 없으면 유실됨.
5. producer가 모든 메시지 전송을 마치면 `http://localhost:8081/pubsub/stop` API를 호출함. stop API는 컨테이너에서 리스너를 제거(thread.join 없음)하고 즉시 최종 결과 값을 반환.

<img width="1948" height="1358" alt="mermaid-diagram (4)" src="https://github.com/user-attachments/assets/770d8982-dd90-4c95-8923-564a3b12c562" />

---

## 실제 벤치마크 결과

로컬 환경(Windows 11, Docker Redis, MySQL)에서 각 5회 반복 측정한 결과임.

### 1,000 메시지 (5회 측정)

| 회차 | Queue | Stream XREAD | Stream Group | Pub/Sub |
|:---:|---:|---:|---:|---:|
| 1 | 32.78 msg/sec | 115.83 msg/sec | 108.50 msg/sec | 82.95 msg/sec |
| 2 | 52.20 msg/sec | 125.74 msg/sec | 152.16 msg/sec | 145.50 msg/sec |
| 3 | 76.45 msg/sec | 200.20 msg/sec | 134.46 msg/sec | 140.96 msg/sec |
| 4 | 47.81 msg/sec | 157.31 msg/sec | 147.84 msg/sec | 131.67 msg/sec |
| 5 | 41.32 msg/sec | 139.68 msg/sec | 143.12 msg/sec | 142.21 msg/sec |
| **평균** | **50.11 msg/sec** | **147.75 msg/sec** | **137.22 msg/sec** | **128.66 msg/sec** |

### 10,000 메시지 (5회 측정)

| 회차 | Queue | Stream XREAD | Stream Group | Pub/Sub |
|:---:|---:|---:|---:|---:|
| 1 | 97.95 msg/sec | 145.58 msg/sec | 65.82 msg/sec | 85.21 msg/sec |
| 2 | 67.56 msg/sec | 125.37 msg/sec | 121.77 msg/sec | 215.75 msg/sec |
| 3 | 131.97 msg/sec | 184.18 msg/sec | 175.27 msg/sec | 163.58 msg/sec |
| 4 | 29.60 msg/sec | 302.21 msg/sec | 272.66 msg/sec | 255.51 msg/sec |
| 5 | 157.83 msg/sec | 269.98 msg/sec | 273.20 msg/sec | 220.51 msg/sec |
| **평균** | **96.98 msg/sec** | **205.46 msg/sec** | **181.74 msg/sec** | **188.11 msg/sec** |

### 결과 분석

#### Queue가 가장 느린 이유

Queue는 `BRPOP`으로 메시지를 **한 건씩** 처리함. Stream 패턴과 달리 배치 처리가 없어 메시지 1,000건을 소비하려면 Redis 왕복을 1,000번 수행해야 함. DB I/O도 건별로 발생하므로 누적 지연이 큼. 또한 1초 timeout의 blocking 특성상 큐가 순간적으로 비면 최대 1초 낭비가 생김. 이로 인해 측정 편차도 크고(29 ~ 157 msg/sec), 같은 조건에서도 결과가 크게 흔들림.

#### Stream XREAD가 가장 빠른 이유

`XREAD BLOCK` 한 번에 최대 100건을 읽어 `saveAll()`로 일괄 DB 저장함. Redis 왕복 횟수와 DB 왕복 횟수가 모두 최대 1/100로 줄어들어 처리량이 높음. 메시지 수가 많을수록(10,000건 기준 평균 205 msg/sec) 배치 효율이 더 두드러짐.

#### Stream Group이 XREAD보다 약간 느린 이유

동일한 배치 처리 구조이지만, 메시지 처리 후 반드시 `XACK`를 호출해야 함. 배치당 한 번의 추가 Redis 명령이 발생하며, Consumer Group 상태 관리(PEL) 오버헤드도 더해짐. 기능(장애 재처리, 다중 컨슈머)을 얻는 대신 소폭 성능을 희생하는 구조임.

#### Pub/Sub의 특성

이벤트 드리븐 방식으로 메시지가 도착하는 즉시 `onMessage()` 콜백이 호출됨. 폴링 오버헤드가 없어 낮은 지연 특성을 가지지만, 콜백은 메시지 **한 건씩** 처리되므로 DB I/O 배치 효율은 Queue와 동일하게 낮음. 10,000건 기준 평균 188 msg/sec로 Stream Group(181 msg/sec)과 유사한 수준임. 단, 메시지가 영속되지 않아 Consumer가 오프라인 상태이면 유실됨.

#### 분산이 큰 이유

모든 패턴에서 측정 편차가 상당함. 주요 원인은 다음과 같다고 예상.
- **MySQL 커넥션 풀 경쟁**: DB 저장 부하가 높아질수록 커넥션 대기 지연이 발생함.
- **로컬 실행 환경**: Producer, Consumer, Redis, MySQL이 모두 같은 머신에서 동작하므로 OS 스케줄러 및 메모리 압박의 영향을 받음.

뭔가 결과를 무조건적으로 따른다기보다는 queue,stream-xread,stream-group,pubsub의 경향성에 대해서 생각을 해보는것이 좋을듯하다.

---

## API

### Producer (`:8080`)

| 메서드 | 경로 | Redis 명령어 |
|---|---|---|
| POST | `/queue/send` | `LPUSH` |
| POST | `/stream-xread/send` | `XADD` |
| POST | `/stream-group/send` | `XADD` |
| POST | `/pubsub/send` | `PUBLISH` |

- **요청 본문**: 메시지 문자열 (생략 시 기본값 `"benchmark-message"`)
- **응답**: `200 OK` / `ok`

### Consumer (`:8081`)

| 메서드 | 경로 | 설명 |
|---|---|---|
| POST | `/{pattern}/start` | Consumer 시작 |
| POST | `/{pattern}/stop` | Consumer 종료 + 최종 결과 반환 |
| GET | `/{pattern}/stats` | 실시간 통계 조회 |
| GET | `/{pattern}/results` | DB에서 과거 결과 목록 조회 |

`{pattern}` = `queue` \| `stream-xread` \| `stream-group` \| `pubsub`

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

## 설정

### Redis 연결

두 서버 모두 `localhost:6379`에 연결됨. Producer는 Lettuce 커넥션 풀을 사용함.

```properties
# producer/src/main/resources/application.properties
spring.data.redis.lettuce.pool.max-active=20
spring.data.redis.lettuce.pool.max-idle=10
spring.data.redis.lettuce.pool.min-idle=5
```

### MySQL (Consumer 결과 저장)

```properties
# consumer/src/main/resources/application.properties
spring.datasource.url=jdbc:mysql://localhost:3306/benchmark
spring.jpa.hibernate.ddl-auto=update
```

벤치마크 결과는 MySQL에 저장되며, `GET /{pattern}/results`로 조회 가능.

---

## 참고사항

- **Pub/Sub**: Consumer가 구독 중인 동안 발행된 메시지만 수신됨. `/start` 이전에 전송된 메시지는 유실됨.
- **Stream Group**: Consumer 그룹(`benchmark-group`)은 Consumer `/start` 호출 시 자동 생성됨. Producer에서 별도로 그룹을 생성할 필요 없음.
- **Stream 메시지 형식**: Stream 패턴(XREAD/Group)은 메시지를 해시맵(`{"payload": "message-1"}`) 형태로 저장함.
- **포트 충돌**: Consumer는 `8081`, Producer는 `8080`으로 고정됨.
