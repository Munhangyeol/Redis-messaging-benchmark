package com.example.consumer.pubsub;

import com.example.consumer.common.BenchmarkResult;
import com.example.consumer.common.BenchmarkResultRepository;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
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
public class PubSubConsumer implements MessageListener {

    static final String CHANNEL = "redis:pubsub";

    private final RedisMessageListenerContainer listenerContainer;
    private final BenchmarkResultRepository resultRepository;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong messageCount = new AtomicLong(0);
    private volatile Instant startTime;

    public PubSubConsumer(RedisMessageListenerContainer listenerContainer,
                          BenchmarkResultRepository resultRepository) {
        this.listenerContainer = listenerContainer;
        this.resultRepository = resultRepository;
    }

    public Map<String, Object> start() {
        if (!running.compareAndSet(false, true)) {
            return Map.of("status", "already_running", "totalMessages", messageCount.get());
        }
        messageCount.set(0);
        startTime = Instant.now();
        listenerContainer.addMessageListener(this, new ChannelTopic(CHANNEL));
        return Map.of("status", "started", "channel", CHANNEL);
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        messageCount.incrementAndGet();
    }

    public Map<String, Object> stop() {
        if (!running.compareAndSet(true, false)) {
            return Map.of("status", "not_running");
        }
        listenerContainer.removeMessageListener(this);

        Instant endTime = Instant.now();
        long count = messageCount.get();
        long durationMs = Duration.between(startTime, endTime).toMillis();
        double throughput = durationMs > 0 ? count * 1000.0 / durationMs : 0;

        resultRepository.save(new BenchmarkResult(
                BenchmarkResult.PatternType.PUBSUB,
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
        stats.put("pattern", "PUBSUB");
        stats.put("running", isRunning);
        stats.put("totalMessages", count);
        stats.put("durationMs", durationMs);
        stats.put("throughput", String.format("%.2f msg/sec", throughput));
        return stats;
    }
}
