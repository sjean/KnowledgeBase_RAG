package com.example.aikb.util;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TextChunkerTest {

    @Test
    void splitShouldReturnEmptyForBlankInput() {
        assertThat(TextChunker.split("   ", 10, 2)).isEmpty();
        assertThat(TextChunker.split(null, 10, 2)).isEmpty();
    }

    @Test
    void splitShouldRespectChunkSizeAndOverlap() {
        List<String> chunks = TextChunker.split("abcdefghij", 4, 1);

        assertThat(chunks).containsExactly("abcd", "defg", "ghij");
    }

    @Test
    void splitShouldStillMoveForwardWhenOverlapIsLargerThanChunkSize() {
        List<String> chunks = TextChunker.split("abcd", 2, 5);

        assertThat(chunks).containsExactly("ab", "bc", "cd");
    }
}
