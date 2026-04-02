# CLAUDE.md

이 파일은 이 저장소에서 작업하는 Claude Code(claude.ai/code)에게 가이드를 제공합니다.

## 프로젝트 목적

이 앱은 Redis 성능 비교 프로젝트의 **컨슈머** 측입니다. 아래 4가지 Redis 메시징 패턴의 성능을 벤치마킹합니다:
- **Queue** (`LIST` 기반, `LPUSH`/`BRPOP` 사용)
- **Stream XREAD** (컨슈머 그룹 없이 스트림 직접 읽기)
- **Stream Group** (컨슈머 그룹을 통한 스트림 읽기)
- **Pub/Sub** (채널 구독 방식)

별도의 **프로듀서** 프로세스(다른 JVM/서비스)가 Python 스크립트를 통해 HTTP 엔드포인트를 10만 번 호출하여 메시지를 전송합니다. 이 컨슈머 프로세스는 해당 메시지를 수신하고 처리하며 처리량을 측정합니다.

## 기술 스택

- Java 17, Spring Boot 4.0.5, Gradle 9.4.1
- Spring Data JPA (결과 저장용 퍼시스턴스 레이어)
- Spring Web MVC (컨슈머 트리거용 REST 엔드포인트)
- Redis (최신 버전), `spring-boot-starter-data-redis` 사용 (`build.gradle`에 추가 필요)

## 명령어

```bash
# 빌드
./gradlew build

# 애플리케이션 실행
./gradlew bootRun

# 전체 테스트 실행
./gradlew test

# 단일 테스트 클래스 실행
./gradlew test --tests "com.example.consumer.SomeTest"

# 테스트 제외 빌드
./gradlew build -x test
```

## 패키지 구조 (목표 아키텍처)

```
com.example.consumer
├── queue/          # LIST 기반 큐 컨슈머
├── streamxread/    # Stream XREAD 컨슈머 (그룹 없음)
├── streamgroup/    # Stream 컨슈머 그룹
└── pubsub/         # Pub/Sub 구독자
```

각 패키지는 다음을 포함해야 합니다:
- `*Consumer` — Redis 리스닝/폴링 로직
- `*Controller` — 컨슈머 시작/중지 및 통계 조회용 REST 엔드포인트

## 핵심 설계 제약

- **컨슈머와 프로듀서는 반드시 별도 프로세스로 실행** — 이 앱은 컨슈머 전용입니다. 프로듀서는 별도의 Spring Boot 애플리케이션입니다.
- 각 컨슈머 패턴은 HTTP로 트리거됩니다 (`GET /queue/start`, `/stream-xread/start` 등).
- 처리량, 지연시간 등 메시지 처리 결과는 비교를 위해 JPA로 저장합니다.
- Redis 연결 설정은 `application.properties`에 작성합니다 (`spring.data.redis.host`, `spring.data.redis.port`).

## 추가 필요 의존성

Redis 기능 구현 전 `spring-boot-starter-data-redis`를 `build.gradle`에 추가해야 합니다:

```groovy
implementation 'org.springframework.boot:spring-boot-starter-data-redis'
```
