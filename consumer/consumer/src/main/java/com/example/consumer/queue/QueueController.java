package com.example.consumer.queue;

import com.example.consumer.common.BenchmarkResult;
import com.example.consumer.common.BenchmarkResultRepository;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/queue")
public class QueueController {

    private final QueueConsumer consumer;
    private final BenchmarkResultRepository resultRepository;

    public QueueController(QueueConsumer consumer, BenchmarkResultRepository resultRepository) {
        this.consumer = consumer;
        this.resultRepository = resultRepository;
    }

    @PostMapping("/start")
    public Map<String, Object> start() {
        return consumer.start();
    }

    @PostMapping("/finish")
    public Map<String, Object> finish() {
        return consumer.finish();
    }

    @GetMapping("/stats")
    public Map<String, Object> stats() {
        return consumer.getStats();
    }

    @GetMapping("/results")
    public List<BenchmarkResult> results() {
        return resultRepository.findByPatternOrderByIdDesc(BenchmarkResult.PatternType.QUEUE);
    }
}
