package com.example.consumer.queue;

import com.example.consumer.common.BenchmarkResult;
import com.example.consumer.common.BenchmarkResultRepository;
import com.example.consumer.common.MessageRecord;
import com.example.consumer.common.MessageRecordRepository;
import jakarta.annotation.PreDestroy;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class QueueConsumer {

    static final String QUEUE_KEY = "redis:queue";

    private final StringRedisTemplate redisTemplate;
    private final BenchmarkResultRepository resultRepository;
    private final MessageRecordRepository messageRecordRepository;

    private final AtomicBoolean accepting = new AtomicBoolean(false);
    private final AtomicLong messageCount = new AtomicLong(0);
    private final AtomicLong processCount = new AtomicLong(0);
    private volatile Instant startTime;
    private volatile Thread consumerThread;

    public QueueConsumer(StringRedisTemplate redisTemplate, BenchmarkResultRepository resultRepository,
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
        consumerThread = new Thread(this::consume, "queue-consumer");
        consumerThread.setDaemon(true);
        consumerThread.start();
        return Map.of("status", "started", "key", QUEUE_KEY);
    }

    private void consume() {
        while (true) {
            try {
                String value;
                if (accepting.get()) {
                    // producer 진행 중: 블로킹 pop (최대 1초 대기)
                    value = redisTemplate.opsForList().rightPop(QUEUE_KEY, 1, TimeUnit.SECONDS);
                } else {
                    // producer 완료 신호 받음: 논블로킹으로 남은 메시지 드레인
                    value = redisTemplate.opsForList().rightPop(QUEUE_KEY);
                    if (value == null) break; // 큐 비었음 → 종료
                }
                if (value != null) {
                    if (processCount.incrementAndGet() % 4 == 0) {
                        throw new RuntimeException("Fault injection: simulated processing error (msg #" + processCount.get() + ")");
                    }
                    messageRecordRepository.save(
                            new MessageRecord(BenchmarkResult.PatternType.QUEUE, value, LocalDateTime.now()));
                    messageCount.incrementAndGet();
                }
            } catch (Exception e) {
                if (!accepting.get()) break;
                System.out.println("Error processing message: " + e.getMessage());
                e.printStackTrace();
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
                BenchmarkResult.PatternType.QUEUE,
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
        stats.put("pattern", "QUEUE");
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
