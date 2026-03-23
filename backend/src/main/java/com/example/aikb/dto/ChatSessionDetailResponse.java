package com.example.aikb.dto;

import java.time.LocalDateTime;
import java.util.List;

public record ChatSessionDetailResponse(
        Long sessionId,
        String title,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        List<ChatMessageResponse> messages
) {
}
