package com.example.consumer.streamxread;

import com.example.consumer.common.BenchmarkResult;
import com.example.consumer.common.BenchmarkResultRepository;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/stream-xread")
public class StreamXReadController {

    private final StreamXReadConsumer consumer;
    private final BenchmarkResultRepository resultRepository;

    public StreamXReadController(StreamXReadConsumer consumer, BenchmarkResultRepository resultRepository) {
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
        return resultRepository.findByPatternOrderByIdDesc(BenchmarkResult.PatternType.STREAM_XREAD);
    }
}
