# Redis Messaging Pattern Benchmark

A benchmark project comparing throughput (msg/sec) across 4 Redis messaging patterns.

| Pattern | Redis Commands | Redis Key |
|---|---|---|
| Queue | `LPUSH` / `BRPOP` | `redis:queue` |
| Stream XREAD | `XADD` / `XREAD BLOCK` | `redis:stream:xread` |
| Stream Group | `XADD` / `XREADGROUP` + `XACK` | `redis:stream:group` |
| Pub/Sub | `PUBLISH` / `SUBSCRIBE` | `redis:pubsub` |

---

## Project Structure

```
redis/
├── producer/               # Message publisher server (Spring Boot, port 8080)
├── consumer/               # Message subscriber server (Spring Boot, port 8081)
├── docker-compose.yml      # Redis container configuration
└── compare_benchmark.py    # Auto benchmark & comparison script for all 4 patterns
```

> Producer and Consumer **must be run as separate processes**.

---

## Tech Stack

| Module | Version |
|---|---|
| Java | 17 |
| Spring Boot (Producer) | 4.0.5 |
| Spring Boot (Consumer) | 3.4.5 |
| Spring Data Redis | - |
| MySQL | 8.x (stores Consumer benchmark results) |
| Redis | latest (Docker) |

---

## Prerequisites

- Java 17+
- Docker (for Redis container)
- MySQL running at `localhost:3306`, database name: `benchmark`
- Python 3.x + `requests` library (for benchmark script)

---

## Getting Started

### 1. Start Redis

```bash
docker-compose up -d
# → Redis: localhost:6379
```

### 2. Start Producer

```bash
cd producer/producer
./gradlew bootRun
# → http://localhost:8080
```

### 3. Start Consumer

```bash
cd consumer/consumer
./gradlew bootRun
# → http://localhost:8081
```

---

## Running the Benchmark

### Python Auto Script (Recommended)

With both Producer and Consumer servers running, execute `compare_benchmark.py` from the root directory.

```bash
pip install requests   # first time only

python compare_benchmark.py
```

It runs all 4 patterns in order (`queue` → `stream-xread` → `stream-group` → `pubsub`). For each pattern it:

1. Calls Consumer `/start`
2. Sends N messages (default `N = 1000`, configurable in the script)
3. Calls Consumer `/stop` and prints the result

Example output:

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

> **Note**: Values above are examples. Actual results vary by machine environment.

### Manual Execution

The following order must be followed for each pattern:

```
1. Consumer /start  →  2. Producer /send × N  →  3. Consumer /stop
```

```bash
# 1. Start Consumer
curl -X POST http://localhost:8081/queue/start

# 2. Send a message via Producer
curl -X POST http://localhost:8080/queue/send -d "message-1"

# 3. Stop Consumer and check results
curl -X POST http://localhost:8081/queue/stop
```

> **Pub/Sub warning**: Only messages published while the Consumer is subscribed are received. Messages sent before `/start` are lost — always start the Consumer first.

---

## API

### Producer (`:8080`)

| Method | Path | Redis Command |
|---|---|---|
| POST | `/queue/send` | `LPUSH` |
| POST | `/stream-xread/send` | `XADD` |
| POST | `/stream-group/send` | `XADD` |
| POST | `/pubsub/send` | `PUBLISH` |

- **Request Body**: message string (defaults to `"benchmark-message"` if omitted)
- **Response**: `200 OK` / `ok`

### Consumer (`:8081`)

| Method | Path | Description |
|---|---|---|
| POST | `/{pattern}/start` | Start consumer |
| POST | `/{pattern}/stop` | Stop consumer + return final result |
| GET | `/{pattern}/stats` | Get real-time stats |
| GET | `/{pattern}/results` | List past results from DB |

`{pattern}` = `queue` \| `stream-xread` \| `stream-group` \| `pubsub`

### `/stop` Response Example

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

## Configuration

### Redis Connection

Both servers connect to `localhost:6379`. The Producer uses a Lettuce connection pool.

```properties
# producer/src/main/resources/application.properties
spring.data.redis.lettuce.pool.max-active=20
spring.data.redis.lettuce.pool.max-idle=10
spring.data.redis.lettuce.pool.min-idle=5
```

### MySQL (Consumer result storage)

```properties
# consumer/src/main/resources/application.properties
spring.datasource.url=jdbc:mysql://localhost:3306/benchmark
spring.jpa.hibernate.ddl-auto=update
```

Benchmark results are persisted in MySQL and can be retrieved via `GET /{pattern}/results`.

---

## Notes

- **Pub/Sub**: Only messages published while the Consumer is subscribed are received. Messages sent before `/start` are lost.
- **Stream Group**: The consumer group (`benchmark-group`) is created automatically when Consumer `/start` is called. The Producer does not need to create the group.
- **Stream message format**: Stream patterns (XREAD/Group) store messages as a hash map (`{"payload": "message-1"}`).
- **Port conflicts**: Consumer is fixed at `8081`, Producer at `8080`.
