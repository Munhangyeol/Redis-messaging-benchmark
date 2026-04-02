package com.example.producer.streamxread;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/stream-xread")
public class StreamXReadController {

    private final StreamXReadProducer streamXReadProducer;

    public StreamXReadController(StreamXReadProducer streamXReadProducer) {
        this.streamXReadProducer = streamXReadProducer;
    }

    @PostMapping("/send")
    public ResponseEntity<String> send(@RequestBody(required = false) String message) {
        if (message == null || message.isBlank()) {
            message = "benchmark-message";
        }
        streamXReadProducer.send(message);
        return ResponseEntity.ok("ok");
    }
}
