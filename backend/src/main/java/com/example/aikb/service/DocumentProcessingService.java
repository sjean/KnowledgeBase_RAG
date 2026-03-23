package com.example.aikb.service;

import com.example.aikb.config.AppProperties;
import com.example.aikb.entity.DocumentRecord;
import com.example.aikb.entity.DocumentStatus;
import com.example.aikb.repository.DocumentRecordRepository;
import com.example.aikb.util.TextChunker;
import com.example.aikb.util.TikaParserUtil;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
public class DocumentProcessingService {

    private static final Logger log = LoggerFactory.getLogger(DocumentProcessingService.class);

    private final DocumentRecordRepository documentRecordRepository;
    private final TikaParserUtil tikaParserUtil;
    private final AppProperties properties;
    private final EmbeddingService embeddingService;
    private final MilvusVectorService milvusVectorService;
    private final ChatCacheService chatCacheService;

    public DocumentProcessingService(DocumentRecordRepository documentRecordRepository,
                                     TikaParserUtil tikaParserUtil,
                                     AppProperties properties,
                                     EmbeddingService embeddingService,
                                     MilvusVectorService milvusVectorService,
                                     ChatCacheService chatCacheService) {
        this.documentRecordRepository = documentRecordRepository;
        this.tikaParserUtil = tikaParserUtil;
        this.properties = properties;
        this.embeddingService = embeddingService;
        this.milvusVectorService = milvusVectorService;
        this.chatCacheService = chatCacheService;
    }

    @Async
    public void processDocument(Long documentId) {
        try {
            DocumentRecord documentRecord = documentRecordRepository.findById(documentId)
                    .orElseThrow(() -> new IllegalArgumentException("Document not found: " + documentId));
            updateStatus(documentRecord, DocumentStatus.PARSING, null, 0);
            String text = tikaParserUtil.parseText(Path.of(documentRecord.getStoragePath()));
            List<String> chunks = TextChunker.split(
                    text,
                    properties.getRag().getChunkSize(),
                    properties.getRag().getChunkOverlap()
            );

            updateStatus(documentRecord, DocumentStatus.EMBEDDING, null, chunks.size());
            List<List<Float>> vectors = new ArrayList<>();
            for (String chunk : chunks) {
                vectors.add(embeddingService.embed(chunk));
            }
            milvusVectorService.storeChunks(
                    documentRecord.getUserId(),
                    documentRecord.getId(),
                    documentRecord.getFileName(),
                    chunks,
                    vectors
            );

            updateStatus(documentRecord, DocumentStatus.READY, null, chunks.size());
            chatCacheService.evictUser(documentRecord.getUserId());
        } catch (Exception exception) {
            log.error("Async document processing failed, documentId={}", documentId, exception);
            documentRecordRepository.findById(documentId).ifPresent(record ->
                    updateStatus(record, DocumentStatus.FAILED, exception.getMessage(), record.getChunkCount())
            );
        }
    }

    private void updateStatus(DocumentRecord documentRecord, DocumentStatus status, String errorMessage, Integer chunkCount) {
        documentRecord.setStatus(status);
        documentRecord.setErrorMessage(errorMessage);
        if (chunkCount != null) {
            documentRecord.setChunkCount(chunkCount);
        }
        documentRecord.setUpdatedAt(LocalDateTime.now());
        documentRecordRepository.save(documentRecord);
    }
}
