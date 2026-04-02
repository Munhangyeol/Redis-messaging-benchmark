package com.example.consumer.streamxread;

import com.example.consumer.common.BenchmarkResult;
import com.example.consumer.common.BenchmarkResultRepository;
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

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong messageCount = new AtomicLong(0);
    private volatile Instant startTime;
    private volatile Thread consumerThread;
    private volatile ReadOffset readOffset;

    public StreamXReadConsumer(StringRedisTemplate redisTemplate, BenchmarkResultRepository resultRepository) {
        this.redisTemplate = redisTemplate;
        this.resultRepository = resultRepository;
    }

    public Map<String, Object> start() {
        if (!running.compareAndSet(false, true)) {
            return Map.of("status", "already_running", "totalMessages", messageCount.get());
        }
        messageCount.set(0);
        startTime = Instant.now();
        readOffset = ReadOffset.latest(); // '$': 컨슈머 시작 이후의 신규 메시지만 읽음
        consumerThread = new Thread(this::consume, "stream-xread-consumer");
        consumerThread.setDaemon(true);
        consumerThread.start();
        return Map.of("status", "started", "key", STREAM_KEY);
    }

    @SuppressWarnings("unchecked")
    private void consume() {
        while (running.get()) {
            try {
                List<MapRecord<String, Object, Object>> records = (List<MapRecord<String, Object, Object>>)
                        (List<?>) redisTemplate.opsForStream().read(
                                StreamReadOptions.empty().block(Duration.ofSeconds(1)).count(100),
                                StreamOffset.create(STREAM_KEY, readOffset)
                        );
                if (records != null && !records.isEmpty()) {
                    messageCount.addAndGet(records.size());
                    // 다음 읽기를 위해 마지막 수신 ID 이후부터 읽도록 오프셋 갱신
                    String lastId = records.get(records.size() - 1).getId().getValue();
                    readOffset = ReadOffset.from(lastId);
                }
            } catch (Exception e) {
                if (running.get()) {
                    try { Thread.sleep(500); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                }
            }
        }
    }

    public Map<String, Object> stop() {
        if (!running.compareAndSet(true, false)) {
            return Map.of("status", "not_running");
        }
        if (consumerThread != null) {
            try {
                consumerThread.join(3000);
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
        return buildStats(count, durationMs, throughput, running.get());
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
        running.set(false);
    }
}
