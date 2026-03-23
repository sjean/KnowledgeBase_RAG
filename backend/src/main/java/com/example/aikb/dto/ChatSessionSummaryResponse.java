package com.example.aikb.dto;

import java.time.LocalDateTime;

public record ChatSessionSummaryResponse(
        Long sessionId,
        String title,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
