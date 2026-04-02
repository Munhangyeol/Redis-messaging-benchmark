package com.example.consumer.queue;

import com.example.consumer.common.BenchmarkResult;
import com.example.consumer.common.BenchmarkResultRepository;
import jakarta.annotation.PreDestroy;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class QueueConsumer {

    static final String QUEUE_KEY = "redis:queue";

    private final StringRedisTemplate redisTemplate;
    private final BenchmarkResultRepository resultRepository;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong messageCount = new AtomicLong(0);
    private volatile Instant startTime;
    private volatile Thread consumerThread;

    public QueueConsumer(StringRedisTemplate redisTemplate, BenchmarkResultRepository resultRepository) {
        this.redisTemplate = redisTemplate;
        this.resultRepository = resultRepository;
    }

    public Map<String, Object> start() {
        if (!running.compareAndSet(false, true)) {
            return Map.of("status", "already_running", "totalMessages", messageCount.get());
        }
        messageCount.set(0);
        startTime = Instant.now();
        consumerThread = new Thread(this::consume, "queue-consumer");
        consumerThread.setDaemon(true);
        consumerThread.start();
        return Map.of("status", "started", "key", QUEUE_KEY);
    }

    private void consume() {
        while (running.get()) {
            try {
                String value = redisTemplate.opsForList().rightPop(QUEUE_KEY);
                if (value != null) {
                    messageCount.incrementAndGet();
                } else {
                    Thread.sleep(10);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                try { Thread.sleep(1000); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
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
        return buildStats(count, durationMs, throughput, running.get());
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
        running.set(false);
    }
}
