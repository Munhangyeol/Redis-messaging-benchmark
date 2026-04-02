package com.example.producer.pubsub;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/pubsub")
public class PubSubController {

    private final PubSubProducer pubSubProducer;

    public PubSubController(PubSubProducer pubSubProducer) {
        this.pubSubProducer = pubSubProducer;
    }

    @PostMapping("/send")
    public ResponseEntity<String> send(@RequestBody(required = false) String message) {
        if (message == null || message.isBlank()) {
            message = "benchmark-message";
        }
        pubSubProducer.send(message);
        return ResponseEntity.ok("ok");
    }
}
