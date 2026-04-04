package com.example.consumer.common;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "message_record")
public class MessageRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private BenchmarkResult.PatternType pattern;

    @Column(length = 1000)
    private String payload;

    private LocalDateTime receivedAt;

    public MessageRecord() {}

    public MessageRecord(BenchmarkResult.PatternType pattern, String payload, LocalDateTime receivedAt) {
        this.pattern = pattern;
        this.payload = payload;
        this.receivedAt = receivedAt;
    }
}
