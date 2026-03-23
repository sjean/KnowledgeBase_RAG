package com.example.aikb.dto;

import java.util.List;

public record PageResponse<T>(
        List<T> items,
        long totalElements,
        int totalPages,
        int page,
        int size
) {
}
