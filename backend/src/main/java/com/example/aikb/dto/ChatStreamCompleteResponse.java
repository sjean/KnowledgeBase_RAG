package com.example.aikb.dto;

import java.time.LocalDateTime;
import java.util.List;

public record ChatStreamCompleteResponse(
        Long sessionId,
        Long assistantMessageId,
        String answer,
        List<SourceItem> sources,
        String toolUsed,
        LocalDateTime createdAt
) {
}
