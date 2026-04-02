package com.example.producer.streamgroup;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/stream-group")
public class StreamGroupController {

    private final StreamGroupProducer streamGroupProducer;

    public StreamGroupController(StreamGroupProducer streamGroupProducer) {
        this.streamGroupProducer = streamGroupProducer;
    }

    @PostMapping("/send")
    public ResponseEntity<String> send(@RequestBody(required = false) String message) {
        if (message == null || message.isBlank()) {
            message = "benchmark-message";
        }
        streamGroupProducer.send(message);
        return ResponseEntity.ok("ok");
    }
}
