package com.example.aikb.dto;

import jakarta.validation.constraints.NotBlank;

public record ChatRequest(
        Long sessionId,
        @NotBlank String question
) {
}
