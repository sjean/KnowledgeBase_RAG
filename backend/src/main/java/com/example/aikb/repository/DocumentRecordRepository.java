package com.example.aikb.repository;

import com.example.aikb.entity.DocumentRecord;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface DocumentRecordRepository extends JpaRepository<DocumentRecord, Long> {

    long countByUserId(Long userId);

    long countByUserIdAndFileNameIgnoreCase(Long userId, String fileName);

    boolean existsByUserIdAndFileNameIgnoreCase(Long userId, String fileName);

    Optional<DocumentRecord> findByIdAndUserId(Long id, Long userId);

    Page<DocumentRecord> findByUserId(Long userId, Pageable pageable);

    Page<DocumentRecord> findByUserIdAndFileNameContainingIgnoreCase(Long userId, String keyword, Pageable pageable);

    @Query("""
            select d from DocumentRecord d
            where lower(d.fileName) like lower(concat('%', :keyword, '%'))
               or d.userId in (
                    select u.id from UserAccount u
                    where lower(u.username) like lower(concat('%', :keyword, '%'))
               )
            """)
    Page<DocumentRecord> searchAllForAdmin(@Param("keyword") String keyword, Pageable pageable);

    List<DocumentRecord> findByUserIdOrderByCreatedAtDesc(Long userId);

    List<DocumentRecord> findAllByOrderByCreatedAtDesc();
}
