package com.example.producer.pubsub;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class PubSubProducer {

    private final StringRedisTemplate redisTemplate;

    public PubSubProducer(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public void send(String message) {
        redisTemplate.convertAndSend("redis:pubsub", message);
    }
}
