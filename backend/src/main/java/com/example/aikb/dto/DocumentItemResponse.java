package com.example.aikb.dto;

import java.time.LocalDateTime;

public record DocumentItemResponse(
        Long documentId,
        String fileName,
        Long ownerUserId,
        String ownerUsername,
        String status,
        int progress,
        Integer chunkCount,
        String errorMessage,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
