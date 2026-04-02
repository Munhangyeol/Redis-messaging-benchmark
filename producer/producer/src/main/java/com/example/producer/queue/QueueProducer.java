package com.example.producer.queue;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class QueueProducer {

    private final StringRedisTemplate redisTemplate;

    public QueueProducer(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public void send(String message) {
        redisTemplate.opsForList().leftPush("redis:queue", message);
    }
}
