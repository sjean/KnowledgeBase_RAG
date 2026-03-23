package com.example.aikb.service;

import com.example.aikb.config.AppProperties;
import com.example.aikb.dto.DocumentItemResponse;
import com.example.aikb.dto.UploadResponse;
import com.example.aikb.entity.DocumentRecord;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DocumentServiceTest {

    @Mock
    private DocumentRecordRepository documentRecordRepository;

    @Mock
    private DocumentProcessingService documentProcessingService;

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
        DocumentService documentService = new DocumentService(properties, documentRecordRepository, documentProcessingService);

        when(documentRecordRepository.saveAndFlush(any(DocumentRecord.class))).thenAnswer(invocation -> {
            DocumentRecord record = invocation.getArgument(0);
            record.setId(101L);
            return record;
        });

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
        DocumentService documentService = new DocumentService(properties, documentRecordRepository, documentProcessingService);

        MockMultipartFile file = new MockMultipartFile("file", "script.exe", "application/octet-stream", new byte[]{1});

        assertThatThrownBy(() -> documentService.upload(1L, file))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Only PDF, Word, and TXT files are supported");
    }

    @Test
    void listDocumentsShouldUseAdminQueryAndInferLegacyReadyStatus() {
        AppProperties properties = new AppProperties();
        DocumentService documentService = new DocumentService(properties, documentRecordRepository, documentProcessingService);
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
        DocumentService documentService = new DocumentService(properties, documentRecordRepository, documentProcessingService);
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
}
