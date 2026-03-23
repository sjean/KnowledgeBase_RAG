package com.example.aikb.dto;

import java.time.LocalDateTime;
import java.util.List;

public record ChatMessageResponse(
        Long messageId,
        String role,
        String content,
        List<SourceItem> sources,
        String toolUsed,
        LocalDateTime createdAt
) {
}
