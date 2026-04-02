package com.example.consumer.streamgroup;

import com.example.consumer.common.BenchmarkResult;
import com.example.consumer.common.BenchmarkResultRepository;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/stream-group")
public class StreamGroupController {

    private final StreamGroupConsumer consumer;
    private final BenchmarkResultRepository resultRepository;

    public StreamGroupController(StreamGroupConsumer consumer, BenchmarkResultRepository resultRepository) {
        this.consumer = consumer;
        this.resultRepository = resultRepository;
    }

    @PostMapping("/start")
    public Map<String, Object> start() {
        return consumer.start();
    }

    @PostMapping("/stop")
    public Map<String, Object> stop() {
        return consumer.stop();
    }

    @GetMapping("/stats")
    public Map<String, Object> stats() {
        return consumer.getStats();
    }

    @GetMapping("/results")
    public List<BenchmarkResult> results() {
        return resultRepository.findByPatternOrderByIdDesc(BenchmarkResult.PatternType.STREAM_GROUP);
    }
}
