package com.example.aikb.repository;

import com.example.aikb.entity.DocumentRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DocumentRecordRepository extends JpaRepository<DocumentRecord, Long> {

    long countByUserId(Long userId);

    long countByUserIdAndFileNameIgnoreCase(Long userId, String fileName);

    boolean existsByUserIdAndFileNameIgnoreCase(Long userId, String fileName);

    Optional<DocumentRecord> findByIdAndUserId(Long id, Long userId);

    List<DocumentRecord> findByUserIdOrderByCreatedAtDesc(Long userId);

    List<DocumentRecord> findAllByOrderByCreatedAtDesc();
}
