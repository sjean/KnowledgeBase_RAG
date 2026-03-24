package com.example.aikb.service;

import com.example.aikb.config.AppProperties;
import com.example.aikb.dto.SourceItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class RagService {

    private static final Logger log = LoggerFactory.getLogger(RagService.class);

    private final EmbeddingService embeddingService;
    private final MilvusVectorService milvusVectorService;
    private final DocumentService documentService;
    private final KeywordRetrievalService keywordRetrievalService;
    private final AppProperties properties;

    public RagService(EmbeddingService embeddingService,
                      MilvusVectorService milvusVectorService,
                      DocumentService documentService,
                      KeywordRetrievalService keywordRetrievalService,
                      AppProperties properties) {
        this.embeddingService = embeddingService;
        this.milvusVectorService = milvusVectorService;
        this.documentService = documentService;
        this.keywordRetrievalService = keywordRetrievalService;
        this.properties = properties;
    }

    public RagResult retrieve(Long userId, boolean admin, String question) {
        List<Float> queryVector = embeddingService.embed(question);
        List<String> preferredFileNames = documentService.detectMentionedFileNames(userId, admin, question);
        List<SourceItem> vectorSources = milvusVectorService.search(
                userId,
                queryVector,
                properties.getRag().getTopK(),
                admin,
                preferredFileNames
        );
        if (vectorSources.isEmpty() && !preferredFileNames.isEmpty()) {
            vectorSources = milvusVectorService.search(
                    userId,
                    queryVector,
                    properties.getRag().getTopK(),
                    admin
            );
        }
        List<SourceItem> keywordSources = keywordRetrievalService.search(
                userId,
                admin,
                question,
                preferredFileNames,
                properties.getRag().getTopK()
        );
        List<SourceItem> sources = mergeHybridSources(keywordSources, vectorSources, properties.getRag().getTopK());
        String context = sources.stream()
                .map(source -> "[source=%s]\n%s".formatted(source.fileName(), source.content()))
                .reduce((a, b) -> a + "\n\n" + b)
                .orElse("无可用知识库上下文。");

        log.info("RAG context for user {}, preferredFiles={}, keywordHits={}, vectorHits={}: {}",
                userId, preferredFileNames, keywordSources.size(), vectorSources.size(), context);
        return new RagResult(context, sources);
    }

    private List<SourceItem> mergeHybridSources(List<SourceItem> keywordSources, List<SourceItem> vectorSources, int topK) {
        java.util.LinkedHashMap<String, SourceItem> merged = new java.util.LinkedHashMap<>();
        keywordSources.forEach(source -> merged.putIfAbsent(buildSourceKey(source), source));
        vectorSources.forEach(source -> merged.putIfAbsent(buildSourceKey(source), source));
        return merged.values().stream()
                .limit(Math.max(topK, 1))
                .toList();
    }

    private String buildSourceKey(SourceItem source) {
        return source.fileName() + "::" + source.chunkId();
    }

    public record RagResult(String context, List<SourceItem> sources) {
    }
}
