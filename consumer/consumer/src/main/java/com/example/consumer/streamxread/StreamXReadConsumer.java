package com.example.consumer.streamxread;

import com.example.consumer.common.BenchmarkResult;
import com.example.consumer.common.BenchmarkResultRepository;
import com.example.consumer.common.MessageRecord;
import com.example.consumer.common.MessageRecordRepository;
import jakarta.annotation.PreDestroy;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

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
public class StreamXReadConsumer {

    static final String STREAM_KEY = "redis:stream:xread";

    private final StringRedisTemplate redisTemplate;
    private final BenchmarkResultRepository resultRepository;
    private final MessageRecordRepository messageRecordRepository;

    private final AtomicBoolean accepting = new AtomicBoolean(false);
    private final AtomicLong messageCount = new AtomicLong(0);
    private volatile Instant startTime;
    private volatile Thread consumerThread;
    private volatile ReadOffset readOffset;

    public StreamXReadConsumer(StringRedisTemplate redisTemplate, BenchmarkResultRepository resultRepository,
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
        // 이전 실행 스트림 데이터 초기화: 삭제 후 "0-0"부터 읽으면
        // consumer thread가 첫 XREAD 호출 전에 메시지가 도착해도 누락 없음 ($는 호출 시점 평가라 race condition 발생)
        redisTemplate.delete(STREAM_KEY);
        readOffset = ReadOffset.from("0-0");
        consumerThread = new Thread(this::consume, "stream-xread-consumer");
        consumerThread.setDaemon(true);
        consumerThread.start();
        return Map.of("status", "started", "key", STREAM_KEY);
    }

    @SuppressWarnings("unchecked")
    private void consume() {
        while (true) {
            try {
                List<MapRecord<String, Object, Object>> records;
                if (accepting.get()) {
                    // producer 진행 중: 블로킹 read (최대 1초 대기)
                    records = (List<MapRecord<String, Object, Object>>) (List<?>)
                            redisTemplate.opsForStream().read(
                                    StreamReadOptions.empty().block(Duration.ofSeconds(1)).count(100),
                                    StreamOffset.create(STREAM_KEY, readOffset)
                            );
                } else {
                    // producer 완료 신호 받음: 논블로킹으로 남은 메시지 드레인
                    records = (List<MapRecord<String, Object, Object>>) (List<?>)
                            redisTemplate.opsForStream().read(
                                    StreamReadOptions.empty().count(100),
                                    StreamOffset.create(STREAM_KEY, readOffset)
                            );
                    if (records == null || records.isEmpty()) break; // 스트림 비었음 → 종료
                }
                if (records != null && !records.isEmpty()) {
                    List<MessageRecord> batch = records.stream()
                            .map(r -> new MessageRecord(BenchmarkResult.PatternType.STREAM_XREAD,
                                    String.valueOf(r.getValue().get("payload")), LocalDateTime.now()))
                            .toList();
                    messageRecordRepository.saveAll(batch);
                    messageCount.addAndGet(records.size());
                    String lastId = records.get(records.size() - 1).getId().getValue();
                    readOffset = ReadOffset.from(lastId);
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
                BenchmarkResult.PatternType.STREAM_XREAD,
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
        stats.put("pattern", "STREAM_XREAD");
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
