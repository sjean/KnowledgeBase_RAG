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
    private final AppProperties properties;

    public RagService(EmbeddingService embeddingService,
                      MilvusVectorService milvusVectorService,
                      AppProperties properties) {
        this.embeddingService = embeddingService;
        this.milvusVectorService = milvusVectorService;
        this.properties = properties;
    }

    public RagResult retrieve(Long userId, boolean admin, String question) {
        List<Float> queryVector = embeddingService.embed(question);
        List<SourceItem> sources = milvusVectorService.search(
                userId,
                queryVector,
                properties.getRag().getTopK(),
                admin
        );
        String context = sources.stream()
                .map(source -> "[source=%s]\n%s".formatted(source.fileName(), source.content()))
                .reduce((a, b) -> a + "\n\n" + b)
                .orElse("无可用知识库上下文。");

        log.info("RAG context for user {}: {}", userId, context);
        return new RagResult(context, sources);
    }

    public record RagResult(String context, List<SourceItem> sources) {
    }
}
