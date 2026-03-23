package com.example.aikb.dto;

public record UploadResponse(
        Long documentId,
        String fileName,
        String status,
        int progress,
        String message
) {
}
