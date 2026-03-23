package com.example.aikb.util;

import java.util.ArrayList;
import java.util.List;

public final class TextChunker {

    private TextChunker() {
    }

    public static List<String> split(String text, int chunkSize, int overlap) {
        String normalized = text == null ? "" : text.replace("\r", "").trim();
        List<String> chunks = new ArrayList<>();
        if (normalized.isBlank()) {
            return chunks;
        }

        int start = 0;
        while (start < normalized.length()) {
            int end = Math.min(normalized.length(), start + chunkSize);
            chunks.add(normalized.substring(start, end).trim());
            if (end == normalized.length()) {
                break;
            }
            start = Math.max(end - overlap, start + 1);
        }
        return chunks;
    }
}
