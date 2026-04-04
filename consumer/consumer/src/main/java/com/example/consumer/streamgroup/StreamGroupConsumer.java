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

    private final AtomicBoolean accepting = new AtomicBoolean(false);
    private final AtomicLong messageCount = new AtomicLong(0);
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

        while (true) {
            try {
                List<MapRecord<String, Object, Object>> records;
                if (accepting.get()) {
                    // producer 진행 중: 블로킹 read (최대 1초 대기)
                    records = (List<MapRecord<String, Object, Object>>) (List<?>)
                            redisTemplate.opsForStream().read(
                                    consumer,
                                    StreamReadOptions.empty().block(Duration.ofSeconds(1)).count(100),
                                    StreamOffset.create(STREAM_KEY, ReadOffset.lastConsumed())
                            );
                } else {
                    // producer 완료 신호 받음: 논블로킹으로 남은 메시지 드레인
                    records = (List<MapRecord<String, Object, Object>>) (List<?>)
                            redisTemplate.opsForStream().read(
                                    consumer,
                                    StreamReadOptions.empty().count(100),
                                    StreamOffset.create(STREAM_KEY, ReadOffset.lastConsumed())
                            );
                    if (records == null || records.isEmpty()) break; // 스트림 비었음 → 종료
                }
                if (records != null && !records.isEmpty()) {
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
                }
            } catch (Exception e) {
                if (!accepting.get()) break;
                try { Thread.sleep(500); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
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
