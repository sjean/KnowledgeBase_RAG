package com.example.aikb.service;

import org.junit.jupiter.api.Test;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;

import static org.assertj.core.api.Assertions.assertThat;

class ChatCacheServiceTest {

    @Test
    void evictUserShouldRemoveOnlyCurrentUsersEntries() {
        ConcurrentMapCacheManager cacheManager = new ConcurrentMapCacheManager("chatAnswers");
        cacheManager.getCache("chatAnswers").put("7:hello", "answer-1");
        cacheManager.getCache("chatAnswers").put("8:hello", "answer-2");

        ChatCacheService service = new ChatCacheService(cacheManager);
        service.evictUser(7L);

        assertThat(cacheManager.getCache("chatAnswers").get("7:hello")).isNull();
        assertThat(cacheManager.getCache("chatAnswers").get("8:hello").get()).isEqualTo("answer-2");
    }
}
