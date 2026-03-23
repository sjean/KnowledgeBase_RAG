package com.example.aikb.repository;

import com.example.aikb.entity.ChatSession;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ChatSessionRepository extends JpaRepository<ChatSession, Long> {

    Optional<ChatSession> findByIdAndUserId(Long id, Long userId);

    Page<ChatSession> findByUserId(Long userId, Pageable pageable);

    Page<ChatSession> findByUserIdAndTitleContainingIgnoreCase(Long userId, String keyword, Pageable pageable);
}
