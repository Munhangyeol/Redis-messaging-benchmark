# Redis Producer

Redis 패턴별 성능 비교를 위한 Spring Boot Producer 애플리케이션입니다.

## 기술 스택

- Java 17
- Spring Boot 4.0.5
- Spring Data Redis
- Redis

## 실행 방법

```bash
./gradlew bootRun
```

서버 포트: `8080`

Redis 기본 연결: `localhost:6379`

---

## 패키지 구조

```
com.example.producer
├── queue/
│   ├── QueueProducer.java
│   └── QueueController.java
├── streamxread/
│   ├── StreamXReadProducer.java
│   └── StreamXReadController.java
├── streamgroup/
│   ├── StreamGroupProducer.java
│   └── StreamGroupController.java
└── pubsub/
    ├── PubSubProducer.java
    └── PubSubController.java
```

---

## API 엔드포인트

| 패턴 | 메서드 | URL | Redis 명령 | Redis 키 |
|---|---|---|---|---|
| Queue | POST | `/queue/send` | `LPUSH` | `redis:queue` |
| Stream XREAD | POST | `/stream-xread/send` | `XADD` | `redis:stream:xread` |
| Stream Group | POST | `/stream-group/send` | `XADD` | `redis:stream:group` |
| Pub/Sub | POST | `/pubsub/send` | `PUBLISH` | `redis:pubsub` |

### 요청 형식

- **Content-Type**: `text/plain` 또는 생략 가능
- **Body**: 전송할 메시지 문자열 (생략 시 `"benchmark-message"` 기본값 사용)

```bash
curl -X POST http://localhost:8080/queue/send -d "message-1"
curl -X POST http://localhost:8080/stream-xread/send -d "message-1"
curl -X POST http://localhost:8080/stream-group/send -d "message-1"
curl -X POST http://localhost:8080/pubsub/send -d "message-1"
```

### 응답 형식

```
200 OK
ok
```

---

## Consumer 연동 순서

Consumer는 별도 프로세스로 포트 `8081`에서 실행됩니다.

### Queue / Stream XREAD / Stream Group

```
1. Consumer POST /queue/start        (컨슈머 시작)
2. Producer POST /queue/send × N회  (메시지 전송)
3. Consumer POST /queue/stop         (컨슈머 중지 + 결과 반환)
```

### Pub/Sub (반드시 Consumer 먼저 시작)

```
1. Consumer POST /pubsub/start       (구독 시작 - 필수)
2. Producer POST /pubsub/send × N회 (메시지 발행)
3. Consumer POST /pubsub/stop        (구독 중지 + 결과 반환)
```

> Pub/Sub은 구독 중인 상태에서 발행된 메시지만 수신됩니다.

---

## Python 벤치마크 스크립트 예시

```python
import requests

PRODUCER = "http://localhost:8080"
CONSUMER = "http://localhost:8081"
N = 100_000

def benchmark(pattern):
    send_url = f"{PRODUCER}/{pattern}/send"
    requests.post(f"{CONSUMER}/{pattern}/start")

    for i in range(N):
        requests.post(send_url, data=f"message-{i}")

    result = requests.post(f"{CONSUMER}/{pattern}/stop").json()
    print(result)

benchmark("queue")
benchmark("stream-xread")
benchmark("stream-group")
benchmark("pubsub")
```

---

## application.properties

```properties
spring.application.name=producer
server.port=8080

spring.data.redis.host=localhost
spring.data.redis.port=6379
```
