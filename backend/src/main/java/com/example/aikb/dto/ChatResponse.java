package com.example.aikb.dto;

import java.util.List;

public record ChatResponse(
        Long sessionId,
        Long assistantMessageId,
        String answer,
        List<SourceItem> sources,
        String toolUsed
) {
}
