package com.example.aikb.service;

import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.concurrent.ConcurrentMapCache;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.concurrent.ConcurrentMap;

@Service
public class ChatCacheService {

    private static final String CHAT_CACHE_NAME = "chatAnswers";

    private final CacheManager cacheManager;

    public ChatCacheService(CacheManager cacheManager) {
        this.cacheManager = cacheManager;
    }

    public void evictUser(Long userId) {
        Cache cache = cacheManager.getCache(CHAT_CACHE_NAME);
        if (cache == null) {
            return;
        }

        if (cache instanceof ConcurrentMapCache concurrentMapCache) {
            evictFromConcurrentMap(concurrentMapCache.getNativeCache(), userId + ":");
            return;
        }

        Object nativeCache = cache.getNativeCache();
        if (nativeCache instanceof ConcurrentMap<?, ?> concurrentMap) {
            evictFromConcurrentMap(concurrentMap, userId + ":");
            return;
        }

        cache.clear();
    }

    public void evictAll() {
        Cache cache = cacheManager.getCache(CHAT_CACHE_NAME);
        if (cache != null) {
            cache.clear();
        }
    }

    private void evictFromConcurrentMap(ConcurrentMap<?, ?> cache, String keyPrefix) {
        for (Object key : new ArrayList<>(cache.keySet())) {
            if (key instanceof String stringKey && stringKey.startsWith(keyPrefix)) {
                cache.remove(key);
            }
        }
    }
}
