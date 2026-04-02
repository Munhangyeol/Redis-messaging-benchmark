package com.example.producer.streamxread;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class StreamXReadProducer {

    private final StringRedisTemplate redisTemplate;

    public StreamXReadProducer(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public void send(String message) {
        redisTemplate.opsForStream().add("redis:stream:xread", Map.of("payload", message));
    }
}
