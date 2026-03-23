package com.example.aikb.dto;

public record LoginResponse(
        String token,
        Long userId,
        String username,
        String role
) {
}
