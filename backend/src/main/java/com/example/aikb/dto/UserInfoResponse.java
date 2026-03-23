package com.example.aikb.dto;

public record UserInfoResponse(
        Long userId,
        String username,
        String role
) {
}
