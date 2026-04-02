package com.example.consumer.common;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "benchmark_result")
public class BenchmarkResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PatternType pattern;

    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private long totalMessages;
    private long durationMs;
    private double throughput; // messages/sec

    public enum PatternType {
        QUEUE, STREAM_XREAD, STREAM_GROUP, PUBSUB
    }

    public BenchmarkResult() {}

    public BenchmarkResult(PatternType pattern, LocalDateTime startTime, LocalDateTime endTime,
                           long totalMessages, long durationMs, double throughput) {
        this.pattern = pattern;
        this.startTime = startTime;
        this.endTime = endTime;
        this.totalMessages = totalMessages;
        this.durationMs = durationMs;
        this.throughput = throughput;
    }

    public Long getId() { return id; }
    public PatternType getPattern() { return pattern; }
    public LocalDateTime getStartTime() { return startTime; }
    public LocalDateTime getEndTime() { return endTime; }
    public long getTotalMessages() { return totalMessages; }
    public long getDurationMs() { return durationMs; }
    public double getThroughput() { return throughput; }
}
