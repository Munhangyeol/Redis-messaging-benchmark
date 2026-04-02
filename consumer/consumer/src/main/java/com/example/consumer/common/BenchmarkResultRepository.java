package com.example.consumer.common;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BenchmarkResultRepository extends JpaRepository<BenchmarkResult, Long> {
    List<BenchmarkResult> findByPatternOrderByIdDesc(BenchmarkResult.PatternType pattern);
}
