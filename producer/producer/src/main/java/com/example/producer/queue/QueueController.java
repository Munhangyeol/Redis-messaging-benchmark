package com.example.producer.queue;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/queue")
public class QueueController {

    private final QueueProducer queueProducer;

    public QueueController(QueueProducer queueProducer) {
        this.queueProducer = queueProducer;
    }

    @PostMapping("/send")
    public ResponseEntity<String> send(@RequestBody(required = false) String message) {
        if (message == null || message.isBlank()) {
            message = "benchmark-message";
        }
        queueProducer.send(message);
        return ResponseEntity.ok("ok");
    }
}
