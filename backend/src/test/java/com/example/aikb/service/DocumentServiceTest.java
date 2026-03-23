package com.example.aikb.service;

import com.example.aikb.config.AppProperties;
import com.example.aikb.dto.DocumentItemResponse;
import com.example.aikb.dto.UploadResponse;
import com.example.aikb.entity.DocumentRecord;
import com.example.aikb.entity.DocumentStatus;
import com.example.aikb.security.UserPrincipal;
import com.example.aikb.repository.DocumentRecordRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DocumentServiceTest {

    @Mock
    private DocumentRecordRepository documentRecordRepository;

    @Mock
    private DocumentProcessingService documentProcessingService;

    @Mock
    private MilvusVectorService milvusVectorService;

    @Mock
    private ChatCacheService chatCacheService;

    @TempDir
    Path tempDir;

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void uploadShouldPersistFileAndDispatchAsyncProcessing() throws IOException {
        AppProperties properties = new AppProperties();
        properties.getStorage().setUploadDir(tempDir.toString());
        DocumentService documentService = new DocumentService(
                properties,
                documentRecordRepository,
                documentProcessingService,
                milvusVectorService,
                chatCacheService
        );

        when(documentRecordRepository.saveAndFlush(any(DocumentRecord.class))).thenAnswer(invocation -> {
            DocumentRecord record = invocation.getArgument(0);
            record.setId(101L);
            return record;
        });
        when(documentRecordRepository.existsByUserIdAndFileNameIgnoreCase(5L, "notes.txt")).thenReturn(false);

        MockMultipartFile file = new MockMultipartFile("file", "notes.txt", "text/plain", "hello world".getBytes());
        UploadResponse response = documentService.upload(5L, file);

        assertThat(response.documentId()).isEqualTo(101L);
        assertThat(response.fileName()).isEqualTo("notes.txt");
        assertThat(response.status()).isEqualTo("UPLOADED");
        assertThat(response.progress()).isEqualTo(10);
        try (var files = Files.list(tempDir)) {
            assertThat(files.count()).isEqualTo(1);
        }

        ArgumentCaptor<DocumentRecord> captor = ArgumentCaptor.forClass(DocumentRecord.class);
        verify(documentRecordRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getUserId()).isEqualTo(5L);
        assertThat(captor.getValue().getStoragePath()).contains(tempDir.toString());
        verify(documentProcessingService).processDocument(101L);
    }

    @Test
    void uploadShouldRejectUnsupportedFileType() {
        AppProperties properties = new AppProperties();
        properties.getStorage().setUploadDir(tempDir.toString());
        DocumentService documentService = new DocumentService(
                properties,
                documentRecordRepository,
                documentProcessingService,
                milvusVectorService,
                chatCacheService
        );

        MockMultipartFile file = new MockMultipartFile("file", "script.exe", "application/octet-stream", new byte[]{1});

        assertThatThrownBy(() -> documentService.upload(1L, file))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Only PDF, Word, and TXT files are supported");
    }

    @Test
    void listDocumentsShouldUseAdminQueryAndInferLegacyReadyStatus() {
        AppProperties properties = new AppProperties();
        DocumentService documentService = new DocumentService(
                properties,
                documentRecordRepository,
                documentProcessingService,
                milvusVectorService,
                chatCacheService
        );
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken(new UserPrincipal(1L, "admin", "ADMIN"), null)
        );

        DocumentRecord record = new DocumentRecord();
        record.setId(11L);
        record.setFileName("legacy.txt");
        record.setChunkCount(2);
        record.setCreatedAt(LocalDateTime.now().minusHours(1));
        record.setUpdatedAt(null);

        when(documentRecordRepository.findAllByOrderByCreatedAtDesc()).thenReturn(List.of(record));

        List<DocumentItemResponse> documents = documentService.listDocuments();

        assertThat(documents).hasSize(1);
        assertThat(documents.get(0).status()).isEqualTo("READY");
        assertThat(documents.get(0).progress()).isEqualTo(100);
        assertThat(documents.get(0).updatedAt()).isEqualTo(record.getCreatedAt());
    }

    @Test
    void listDocumentsShouldUseCurrentUserQueryForNonAdmin() {
        AppProperties properties = new AppProperties();
        DocumentService documentService = new DocumentService(
                properties,
                documentRecordRepository,
                documentProcessingService,
                milvusVectorService,
                chatCacheService
        );
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken(new UserPrincipal(9L, "user", "USER"), null)
        );

        DocumentRecord record = new DocumentRecord();
        record.setId(22L);
        record.setFileName("failed.txt");
        record.setChunkCount(0);
        record.setErrorMessage("parse failed");
        record.setCreatedAt(LocalDateTime.now());
        record.setUpdatedAt(LocalDateTime.now());

        when(documentRecordRepository.findByUserIdOrderByCreatedAtDesc(eq(9L))).thenReturn(List.of(record));

        List<DocumentItemResponse> documents = documentService.listDocuments();

        assertThat(documents).hasSize(1);
        assertThat(documents.get(0).status()).isEqualTo("FAILED");
        verify(documentRecordRepository).findByUserIdOrderByCreatedAtDesc(9L);
    }

    @Test
    void uploadShouldRejectDuplicateFileNameForSameUser() {
        AppProperties properties = new AppProperties();
        properties.getStorage().setUploadDir(tempDir.toString());
        DocumentService documentService = new DocumentService(
                properties,
                documentRecordRepository,
                documentProcessingService,
                milvusVectorService,
                chatCacheService
        );
        when(documentRecordRepository.existsByUserIdAndFileNameIgnoreCase(5L, "notes.txt")).thenReturn(true);

        MockMultipartFile file = new MockMultipartFile("file", "notes.txt", "text/plain", "hello world".getBytes());

        assertThatThrownBy(() -> documentService.upload(5L, file))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("A document with the same file name already exists. Delete it first or rename the file.");
    }

    @Test
    void retryShouldResetFailedDocumentAndDispatchAsyncProcessing() throws IOException {
        AppProperties properties = new AppProperties();
        DocumentService documentService = new DocumentService(
                properties,
                documentRecordRepository,
                documentProcessingService,
                milvusVectorService,
                chatCacheService
        );
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken(new UserPrincipal(9L, "user", "USER"), null)
        );

        Path storedFile = Files.createFile(tempDir.resolve("failed.txt"));
        DocumentRecord record = new DocumentRecord();
        record.setId(21L);
        record.setUserId(9L);
        record.setFileName("failed.txt");
        record.setStoragePath(storedFile.toString());
        record.setStatus(DocumentStatus.FAILED);
        record.setChunkCount(2);
        record.setErrorMessage("broken embedding");
        record.setCreatedAt(LocalDateTime.now().minusMinutes(5));
        record.setUpdatedAt(LocalDateTime.now().minusMinutes(1));

        when(documentRecordRepository.findByIdAndUserId(21L, 9L)).thenReturn(java.util.Optional.of(record));
        when(documentRecordRepository.saveAndFlush(record)).thenReturn(record);

        DocumentItemResponse response = documentService.retryDocument(21L);

        assertThat(response.status()).isEqualTo("UPLOADED");
        assertThat(response.progress()).isEqualTo(10);
        assertThat(record.getChunkCount()).isZero();
        assertThat(record.getErrorMessage()).isNull();
        verify(milvusVectorService).deleteDocumentChunks(9L, 21L, "failed.txt", false);
        verify(documentProcessingService).processDocument(21L);
        verify(chatCacheService).evictUser(9L);
    }

    @Test
    void deleteShouldRemoveFileVectorsAndRecord() throws IOException {
        AppProperties properties = new AppProperties();
        DocumentService documentService = new DocumentService(
                properties,
                documentRecordRepository,
                documentProcessingService,
                milvusVectorService,
                chatCacheService
        );
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken(new UserPrincipal(9L, "user", "USER"), null)
        );

        Path storedFile = Files.createFile(tempDir.resolve("guide.txt"));
        DocumentRecord record = new DocumentRecord();
        record.setId(22L);
        record.setUserId(9L);
        record.setFileName("guide.txt");
        record.setStoragePath(storedFile.toString());
        record.setStatus(DocumentStatus.READY);
        record.setCreatedAt(LocalDateTime.now());
        record.setUpdatedAt(LocalDateTime.now());

        when(documentRecordRepository.findByIdAndUserId(22L, 9L)).thenReturn(java.util.Optional.of(record));

        documentService.deleteDocument(22L);

        assertThat(Files.exists(storedFile)).isFalse();
        verify(milvusVectorService).deleteDocumentChunks(9L, 22L, "guide.txt", false);
        verify(documentRecordRepository).delete(record);
        verify(chatCacheService).evictUser(9L);
    }

    @Test
    void deleteShouldRejectProcessingDocument() {
        AppProperties properties = new AppProperties();
        DocumentService documentService = new DocumentService(
                properties,
                documentRecordRepository,
                documentProcessingService,
                milvusVectorService,
                chatCacheService
        );
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken(new UserPrincipal(9L, "user", "USER"), null)
        );

        DocumentRecord record = new DocumentRecord();
        record.setId(23L);
        record.setUserId(9L);
        record.setFileName("running.txt");
        record.setStatus(DocumentStatus.PARSING);

        when(documentRecordRepository.findByIdAndUserId(23L, 9L)).thenReturn(java.util.Optional.of(record));

        assertThatThrownBy(() -> documentService.deleteDocument(23L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Document is being processed and cannot be deleted right now");
        verify(milvusVectorService, never()).deleteDocumentChunks(any(), any(), any(), any(Boolean.class));
    }
}
