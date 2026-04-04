package com.example.consumer.common;

import org.springframework.data.jpa.repository.JpaRepository;

public interface MessageRecordRepository extends JpaRepository<MessageRecord, Long> {
}
