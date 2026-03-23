package com.example.aikb.dto;

import java.util.List;

public record ChatResponse(
        String answer,
        List<SourceItem> sources,
        String toolUsed
) {
}
