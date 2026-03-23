package com.example.aikb.repository;

import com.example.aikb.entity.DocumentRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DocumentRecordRepository extends JpaRepository<DocumentRecord, Long> {

    long countByUserId(Long userId);

    List<DocumentRecord> findByUserIdOrderByCreatedAtDesc(Long userId);

    List<DocumentRecord> findAllByOrderByCreatedAtDesc();
}
