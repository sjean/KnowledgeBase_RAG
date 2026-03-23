package com.example.aikb.service;

import com.example.aikb.config.AppProperties;
import com.example.aikb.entity.DocumentRecord;
import com.example.aikb.entity.DocumentStatus;
import com.example.aikb.repository.DocumentRecordRepository;
import com.example.aikb.util.TikaParserUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DocumentProcessingServiceTest {

    @Mock
    private DocumentRecordRepository documentRecordRepository;

    @Mock
    private TikaParserUtil tikaParserUtil;

    @Mock
    private EmbeddingService embeddingService;

    @Mock
    private MilvusVectorService milvusVectorService;

    @Test
    void processDocumentShouldParseEmbedAndMarkReady() {
        AppProperties properties = new AppProperties();
        properties.getRag().setChunkSize(4);
        properties.getRag().setChunkOverlap(1);
        ConcurrentMapCacheManager cacheManager = new ConcurrentMapCacheManager("chatAnswers");
        cacheManager.getCache("chatAnswers").put("cached-question", "cached-answer");

        DocumentRecord record = new DocumentRecord();
        record.setId(1L);
        record.setUserId(8L);
        record.setFileName("doc.txt");
        record.setStoragePath("/tmp/doc.txt");

        when(documentRecordRepository.findById(1L)).thenReturn(Optional.of(record));
        when(tikaParserUtil.parseText(any(Path.class))).thenReturn("abcde");
        when(embeddingService.embed(any())).thenReturn(List.of(0.1f, 0.2f));

        DocumentProcessingService service = new DocumentProcessingService(
                documentRecordRepository,
                tikaParserUtil,
                properties,
                embeddingService,
                milvusVectorService,
                cacheManager
        );

        service.processDocument(1L);

        assertThat(record.getStatus()).isEqualTo(DocumentStatus.READY);
        assertThat(record.getChunkCount()).isEqualTo(2);
        assertThat(cacheManager.getCache("chatAnswers").get("cached-question")).isNull();
        verify(milvusVectorService).storeChunks(eq(8L), eq("doc.txt"), eq(List.of("abcd", "de")), any());
        verify(documentRecordRepository, atLeastOnce()).save(record);
    }

    @Test
    void processDocumentShouldMarkFailedWhenParsingThrows() {
        AppProperties properties = new AppProperties();
        ConcurrentMapCacheManager cacheManager = new ConcurrentMapCacheManager("chatAnswers");

        DocumentRecord record = new DocumentRecord();
        record.setId(2L);
        record.setUserId(3L);
        record.setFileName("broken.txt");
        record.setStoragePath("/tmp/broken.txt");

        when(documentRecordRepository.findById(2L)).thenReturn(Optional.of(record));
        when(tikaParserUtil.parseText(any(Path.class))).thenThrow(new IllegalStateException("parse failed"));

        DocumentProcessingService service = new DocumentProcessingService(
                documentRecordRepository,
                tikaParserUtil,
                properties,
                embeddingService,
                milvusVectorService,
                cacheManager
        );

        service.processDocument(2L);

        assertThat(record.getStatus()).isEqualTo(DocumentStatus.FAILED);
        assertThat(record.getErrorMessage()).contains("parse failed");
        verify(documentRecordRepository, atLeastOnce()).save(record);
    }
}
