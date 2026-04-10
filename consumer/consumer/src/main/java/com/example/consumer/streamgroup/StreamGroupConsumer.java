package com.example.consumer.streamgroup;

import com.example.consumer.common.BenchmarkResult;
import com.example.consumer.common.BenchmarkResultRepository;
import com.example.consumer.common.MessageRecord;
import com.example.consumer.common.MessageRecordRepository;
import jakarta.annotation.PreDestroy;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class StreamGroupConsumer {

    static final String STREAM_KEY = "redis:stream:group";
    static final String GROUP_NAME = "benchmark-group";
    static final String CONSUMER_NAME = "consumer-1";

    private final StringRedisTemplate redisTemplate;
    private final BenchmarkResultRepository resultRepository;
    private final MessageRecordRepository messageRecordRepository;

    /**
     * PEL 재처리 최대 재시도 횟수.
     * 이 횟수를 초과하면 해당 배치는 ACK 없이 포기(메시지는 PEL에 잔류, 운영 환경에서는 별도 알람/DLQ 처리 필요).
     */
    private static final int MAX_PEL_RETRIES = 3;

    private final AtomicBoolean accepting = new AtomicBoolean(false);
    private final AtomicLong messageCount = new AtomicLong(0);
    private final AtomicLong processCount = new AtomicLong(0);
    private volatile Instant startTime;
    private volatile Thread consumerThread;

    public StreamGroupConsumer(StringRedisTemplate redisTemplate, BenchmarkResultRepository resultRepository,
                               MessageRecordRepository messageRecordRepository) {
        this.redisTemplate = redisTemplate;
        this.resultRepository = resultRepository;
        this.messageRecordRepository = messageRecordRepository;
    }

    public Map<String, Object> start() {
        if (!accepting.compareAndSet(false, true)) {
            return Map.of("status", "already_running", "totalMessages", messageCount.get());
        }
        messageCount.set(0);
        processCount.set(0);
        startTime = Instant.now();
        initConsumerGroup();
        consumerThread = new Thread(this::consume, "stream-group-consumer");
        consumerThread.setDaemon(true);
        consumerThread.start();
        return Map.of("status", "started", "key", STREAM_KEY, "group", GROUP_NAME, "consumer", CONSUMER_NAME);
    }

    /**
     * XGROUP CREATE key group $ MKSTREAM
     * 스트림이 없어도 자동 생성(MKSTREAM), 그룹이 이미 존재하면(BUSYGROUP) 무시
     */
    private void initConsumerGroup() {
        // 그룹 삭제 (PEL 포함)
        try {
            redisTemplate.opsForStream().destroyGroup(STREAM_KEY, GROUP_NAME);
        } catch (Exception ignored) {}
        // 스트림 자체도 삭제: 이전 실행 메시지가 누적된 채로 새 그룹 offset이 잘못 설정되는 것 방지
        try {
            redisTemplate.delete(STREAM_KEY);
        } catch (Exception ignored) {}
        try {
            redisTemplate.execute((org.springframework.data.redis.core.RedisCallback<Object>) connection ->
                    connection.execute("XGROUP",
                            "CREATE".getBytes(StandardCharsets.UTF_8),
                            STREAM_KEY.getBytes(StandardCharsets.UTF_8),
                            GROUP_NAME.getBytes(StandardCharsets.UTF_8),
                            "$".getBytes(StandardCharsets.UTF_8),
                            "MKSTREAM".getBytes(StandardCharsets.UTF_8)
                    )
            );
        } catch (Exception e) {
            throw new RuntimeException("컨슈머 그룹 초기화 실패: " + e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private void consume() {
        Consumer consumer = Consumer.from(GROUP_NAME, CONSUMER_NAME);
        // PEL 재처리 여부 추적: 예외 발생 시 true → 다음 루프에서 "0-0"으로 PEL 먼저 읽어서 재처리
        boolean hasPending = false;
        // 연속 PEL 재처리 실패 횟수: MAX_PEL_RETRIES 초과 시 해당 배치 포기
        int pelRetryCount = 0;

        while (true) {
            try {
                List<MapRecord<String, Object, Object>> records;
                if (accepting.get()) {
                    if (hasPending) {
                        // [재시도] PEL(미ack) 메시지를 "0-0"부터 읽어 재처리
                        records = (List<MapRecord<String, Object, Object>>) (List<?>)
                                redisTemplate.opsForStream().read(
                                        consumer,
                                        StreamReadOptions.empty().count(100),
                                        StreamOffset.create(STREAM_KEY, ReadOffset.from("0-0"))
                                );
                        if (records == null || records.isEmpty()) {
                            // PEL 소진: 재처리 성공으로 간주하고 새 메시지 읽기로 복귀
                            hasPending = false;
                            pelRetryCount = 0;
                            continue;
                        }
                    } else {
                        // 정상 경로: 새 메시지 블로킹 read (최대 1초 대기)
                        records = (List<MapRecord<String, Object, Object>>) (List<?>)
                                redisTemplate.opsForStream().read(
                                        consumer,
                                        StreamReadOptions.empty().block(Duration.ofSeconds(1)).count(100),
                                        StreamOffset.create(STREAM_KEY, ReadOffset.lastConsumed())
                                );
                    }
                } else {
                    // producer 완료 신호 받음: PEL 먼저 드레인 후 새 메시지 드레인
                    ReadOffset offset = hasPending ? ReadOffset.from("0-0") : ReadOffset.lastConsumed();
                    records = (List<MapRecord<String, Object, Object>>) (List<?>)
                            redisTemplate.opsForStream().read(
                                    consumer,
                                    StreamReadOptions.empty().count(100),
                                    StreamOffset.create(STREAM_KEY, offset)
                            );
                    if (records == null || records.isEmpty()) {
                        if (hasPending) {
                            hasPending = false; // PEL 소진 → 새 메시지 드레인으로 전환
                            pelRetryCount = 0;
                            continue;
                        }
                        break; // 새 메시지도 없음 → 종료
                    }
                }
                if (records != null && !records.isEmpty()) {
                    if (processCount.addAndGet(records.size()) % 4 == 0) {
                        // 장애 주입: ACK 없이 예외 발생 → 메시지가 PEL에 잔류하여 재처리 가능
                        hasPending = true;
                        pelRetryCount++;
                        throw new RuntimeException("Fault injection: simulated processing error (batch ending at msg #" + processCount.get() + ")");
                    }
                    List<MessageRecord> batch = records.stream()
                            .map(r -> new MessageRecord(BenchmarkResult.PatternType.STREAM_GROUP,
                                    String.valueOf(r.getValue().get("payload")), LocalDateTime.now()))
                            .toList();
                    messageRecordRepository.saveAll(batch);
                    messageCount.addAndGet(records.size());
                    RecordId[] ids = records.stream()
                            .map(MapRecord::getId)
                            .toArray(RecordId[]::new);
                    redisTemplate.opsForStream().acknowledge(STREAM_KEY, GROUP_NAME, ids);
                    // ACK 완료: 재시도 상태 초기화
                    hasPending = false;
                    pelRetryCount = 0;
                }
            } catch (Exception e) {
                if (hasPending && pelRetryCount >= MAX_PEL_RETRIES) {
                    // 재시도 한계 초과: 해당 PEL 배치 포기 (메시지는 PEL에 잔류)
                    // 운영 환경에서는 XAUTOCLAIM, DLQ, 알람 등 추가 처리 필요
                    hasPending = false;
                    pelRetryCount = 0;
                } else if (hasPending) {
                    // PEL 재처리 재시도: 지수 백오프로 대기 (100ms, 200ms, 400ms ...)
                    long backoffMs = 100L * (1L << (pelRetryCount - 1));
                    try { Thread.sleep(backoffMs); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                }
                if (!accepting.get()) {
                    if (hasPending) continue; // PEL이 남아있으면 재처리 후 종료
                    break;
                }
                if (!hasPending) {
                    try { Thread.sleep(100); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                }
            }
        }
    }

    public Map<String, Object> finish() {
        if (!accepting.compareAndSet(true, false)) {
            return Map.of("status", "not_running");
        }
        // consumer 스레드가 남은 메시지를 모두 처리하고 자기 종료할 때까지 대기
        if (consumerThread != null) {
            try {
                consumerThread.join(120_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        Instant endTime = Instant.now();
        long count = messageCount.get();
        long durationMs = Duration.between(startTime, endTime).toMillis();
        double throughput = durationMs > 0 ? count * 1000.0 / durationMs : 0;

        resultRepository.save(new BenchmarkResult(
                BenchmarkResult.PatternType.STREAM_GROUP,
                LocalDateTime.ofInstant(startTime, ZoneId.systemDefault()),
                LocalDateTime.ofInstant(endTime, ZoneId.systemDefault()),
                count, durationMs, throughput
        ));
        return buildStats(count, durationMs, throughput, false);
    }

    public Map<String, Object> getStats() {
        long count = messageCount.get();
        long durationMs = startTime != null ? Duration.between(startTime, Instant.now()).toMillis() : 0;
        double throughput = durationMs > 0 ? count * 1000.0 / durationMs : 0;
        return buildStats(count, durationMs, throughput, accepting.get());
    }

    private Map<String, Object> buildStats(long count, long durationMs, double throughput, boolean isRunning) {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("pattern", "STREAM_GROUP");
        stats.put("running", isRunning);
        stats.put("totalMessages", count);
        stats.put("durationMs", durationMs);
        stats.put("throughput", String.format("%.2f msg/sec", throughput));
        return stats;
    }

    @PreDestroy
    public void destroy() {
        accepting.set(false);
    }
}
