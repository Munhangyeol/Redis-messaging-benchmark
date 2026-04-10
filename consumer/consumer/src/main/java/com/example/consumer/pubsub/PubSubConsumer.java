package com.example.consumer.pubsub;

import com.example.consumer.common.BenchmarkResult;
import com.example.consumer.common.BenchmarkResultRepository;
import com.example.consumer.common.MessageRecord;
import com.example.consumer.common.MessageRecordRepository;
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
    private final MessageRecordRepository messageRecordRepository;

    private final AtomicBoolean accepting = new AtomicBoolean(false);
    private final AtomicLong messageCount = new AtomicLong(0);
    // push 기반이므로 onMessage가 listener 스레드에서 동시 호출될 수 있음
    private final AtomicLong inFlight = new AtomicLong(0);
    private final AtomicLong processCount = new AtomicLong(0);
    private volatile Instant startTime;

    public PubSubConsumer(RedisMessageListenerContainer listenerContainer,
                          BenchmarkResultRepository resultRepository,
                          MessageRecordRepository messageRecordRepository) {
        this.listenerContainer = listenerContainer;
        this.resultRepository = resultRepository;
        this.messageRecordRepository = messageRecordRepository;
    }

    public Map<String, Object> start() {
        if (!accepting.compareAndSet(false, true)) {
            return Map.of("status", "already_running", "totalMessages", messageCount.get());
        }
        messageCount.set(0);
        inFlight.set(0);
        processCount.set(0);
        startTime = Instant.now();
        listenerContainer.addMessageListener(this, new ChannelTopic(CHANNEL));
        return Map.of("status", "started", "channel", CHANNEL);
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        if (!accepting.get()) return;
        inFlight.incrementAndGet();
        try {
            if (processCount.incrementAndGet() % 4 == 0) {
                throw new RuntimeException("Fault injection: simulated processing error (msg #" + processCount.get() + ")");
            }
            String payload = new String(message.getBody());
            messageRecordRepository.save(
                    new MessageRecord(BenchmarkResult.PatternType.PUBSUB, payload, LocalDateTime.now()));
            messageCount.incrementAndGet();
        } finally {
            inFlight.decrementAndGet();
        }
    }

    public Map<String, Object> finish() {
        if (!accepting.compareAndSet(true, false)) {
            return Map.of("status", "not_running");
        }
        // 새 메시지 수신 중단
        listenerContainer.removeMessageListener(this);

        // 리스너 제거 전 마지막으로 dispatch된 onMessage 호출이 완료될 때까지 대기
        while (inFlight.get() > 0) {
            try { Thread.sleep(10); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
        }

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
        return buildStats(count, durationMs, throughput, accepting.get());
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
