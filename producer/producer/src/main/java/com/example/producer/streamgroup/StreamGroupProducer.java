package com.example.producer.streamgroup;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class StreamGroupProducer {

    private final StringRedisTemplate redisTemplate;

    public StreamGroupProducer(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public void send(String message) {
        redisTemplate.opsForStream().add("redis:stream:group", Map.of("payload", message));
    }
}
