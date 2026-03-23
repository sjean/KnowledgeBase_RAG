package com.example.aikb.service;

import com.example.aikb.config.AppProperties;
import com.example.aikb.dto.DocumentItemResponse;
import com.example.aikb.dto.UploadResponse;
import com.example.aikb.entity.DocumentRecord;
import com.example.aikb.entity.DocumentStatus;
import com.example.aikb.repository.DocumentRecordRepository;
import com.example.aikb.util.SecurityUtils;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
public class DocumentService {

    private final AppProperties properties;
    private final DocumentRecordRepository documentRecordRepository;
    private final DocumentProcessingService documentProcessingService;
    private final MilvusVectorService milvusVectorService;
    private final ChatCacheService chatCacheService;

    public DocumentService(AppProperties properties,
                           DocumentRecordRepository documentRecordRepository,
                           DocumentProcessingService documentProcessingService,
                           MilvusVectorService milvusVectorService,
                           ChatCacheService chatCacheService) {
        this.properties = properties;
        this.documentRecordRepository = documentRecordRepository;
        this.documentProcessingService = documentProcessingService;
        this.milvusVectorService = milvusVectorService;
        this.chatCacheService = chatCacheService;
    }

    public UploadResponse upload(Long userId, MultipartFile file) {
        String filename = file.getOriginalFilename() == null ? "unknown.txt" : file.getOriginalFilename();
        validateFileType(filename);
        validateDuplicateFileName(userId, filename);

        Path uploadDir = Path.of(properties.getStorage().getUploadDir());
        String storedFileName = UUID.randomUUID() + "-" + filename;
        Path targetPath = uploadDir.resolve(storedFileName);
        persistFile(file, uploadDir, targetPath);

        DocumentRecord documentRecord = new DocumentRecord();
        documentRecord.setUserId(userId);
        documentRecord.setFileName(filename);
        documentRecord.setChunkCount(0);
        documentRecord.setStatus(DocumentStatus.UPLOADED);
        documentRecord.setStoragePath(targetPath.toAbsolutePath().toString());
        documentRecord.setErrorMessage(null);
        documentRecord.setCreatedAt(LocalDateTime.now());
        documentRecord.setUpdatedAt(LocalDateTime.now());
        documentRecordRepository.saveAndFlush(documentRecord);
        documentProcessingService.processDocument(documentRecord.getId());

        return new UploadResponse(documentRecord.getId(), filename, documentRecord.getStatus().name(), progressOf(documentRecord.getStatus()), "文档已上传，后台正在处理中");
    }

    public long countByUser(Long userId) {
        return documentRecordRepository.countByUserId(userId);
    }

    public long countAll() {
        return documentRecordRepository.count();
    }

    public List<DocumentItemResponse> listDocuments() {
        List<DocumentRecord> records = SecurityUtils.isAdmin()
                ? documentRecordRepository.findAllByOrderByCreatedAtDesc()
                : documentRecordRepository.findByUserIdOrderByCreatedAtDesc(SecurityUtils.currentUser().userId());
        return records.stream().map(this::toResponse).toList();
    }

    public DocumentItemResponse retryDocument(Long documentId) {
        DocumentRecord record = loadAccessibleDocument(documentId);
        if (record.getStatus() != DocumentStatus.FAILED) {
            throw new IllegalArgumentException("Only failed documents can be retried");
        }
        ensureStoredFileExists(record);
        milvusVectorService.deleteDocumentChunks(record.getUserId(), record.getId(), record.getFileName(), SecurityUtils.isAdmin());

        record.setStatus(DocumentStatus.UPLOADED);
        record.setChunkCount(0);
        record.setErrorMessage(null);
        record.setUpdatedAt(LocalDateTime.now());
        documentRecordRepository.saveAndFlush(record);
        documentProcessingService.processDocument(record.getId());
        chatCacheService.evictUser(record.getUserId());
        return toResponse(record);
    }

    public void deleteDocument(Long documentId) {
        DocumentRecord record = loadAccessibleDocument(documentId);
        if (isProcessing(record.getStatus())) {
            throw new IllegalArgumentException("Document is being processed and cannot be deleted right now");
        }
        milvusVectorService.deleteDocumentChunks(record.getUserId(), record.getId(), record.getFileName(), SecurityUtils.isAdmin());
        deleteStoredFile(record.getStoragePath());
        documentRecordRepository.delete(record);
        chatCacheService.evictUser(record.getUserId());
    }

    private void validateFileType(String filename) {
        String lower = filename.toLowerCase();
        if (!(lower.endsWith(".pdf") || lower.endsWith(".doc") || lower.endsWith(".docx") || lower.endsWith(".txt"))) {
            throw new IllegalArgumentException("Only PDF, Word, and TXT files are supported");
        }
    }

    private void validateDuplicateFileName(Long userId, String filename) {
        if (documentRecordRepository.existsByUserIdAndFileNameIgnoreCase(userId, filename)) {
            throw new IllegalArgumentException("A document with the same file name already exists. Delete it first or rename the file.");
        }
    }

    private void persistFile(MultipartFile file, Path uploadDir, Path targetPath) {
        try {
            Files.createDirectories(uploadDir);
            Files.copy(file.getInputStream(), targetPath, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to store uploaded file", exception);
        }
    }

    private void deleteStoredFile(String storagePath) {
        if (storagePath == null || storagePath.isBlank()) {
            return;
        }
        try {
            Files.deleteIfExists(Path.of(storagePath));
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to delete stored file", exception);
        }
    }

    private void ensureStoredFileExists(DocumentRecord record) {
        if (record.getStoragePath() == null || record.getStoragePath().isBlank() || Files.notExists(Path.of(record.getStoragePath()))) {
            throw new IllegalArgumentException("Stored file is missing, please upload the document again");
        }
    }

    private DocumentRecord loadAccessibleDocument(Long documentId) {
        if (SecurityUtils.isAdmin()) {
            return documentRecordRepository.findById(documentId)
                    .orElseThrow(() -> new IllegalArgumentException("Document not found"));
        }
        return documentRecordRepository.findByIdAndUserId(documentId, SecurityUtils.currentUser().userId())
                .orElseThrow(() -> new IllegalArgumentException("Document not found"));
    }

    private boolean isProcessing(DocumentStatus status) {
        return status == DocumentStatus.PARSING || status == DocumentStatus.EMBEDDING;
    }

    private DocumentItemResponse toResponse(DocumentRecord record) {
        DocumentStatus status = record.getStatus() == null ? inferLegacyStatus(record) : record.getStatus();
        return new DocumentItemResponse(
                record.getId(),
                record.getFileName(),
                status.name(),
                progressOf(status),
                record.getChunkCount(),
                record.getErrorMessage(),
                record.getCreatedAt(),
                record.getUpdatedAt() == null ? record.getCreatedAt() : record.getUpdatedAt()
        );
    }

    private int progressOf(DocumentStatus status) {
        return switch (status) {
            case UPLOADED -> 10;
            case PARSING -> 35;
            case EMBEDDING -> 75;
            case READY, FAILED -> 100;
        };
    }

    private DocumentStatus inferLegacyStatus(DocumentRecord record) {
        if (record.getErrorMessage() != null && !record.getErrorMessage().isBlank()) {
            return DocumentStatus.FAILED;
        }
        if (record.getChunkCount() != null && record.getChunkCount() > 0) {
            return DocumentStatus.READY;
        }
        return DocumentStatus.UPLOADED;
    }
}
