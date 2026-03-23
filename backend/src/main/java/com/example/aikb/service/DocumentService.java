package com.example.aikb.service;

import com.example.aikb.config.AppProperties;
import com.example.aikb.dto.DocumentItemResponse;
import com.example.aikb.dto.PageResponse;
import com.example.aikb.dto.UploadResponse;
import com.example.aikb.entity.DocumentRecord;
import com.example.aikb.entity.DocumentStatus;
import com.example.aikb.repository.DocumentRecordRepository;
import com.example.aikb.repository.UserAccountRepository;
import com.example.aikb.util.SecurityUtils;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class DocumentService {

    private final AppProperties properties;
    private final DocumentRecordRepository documentRecordRepository;
    private final DocumentProcessingService documentProcessingService;
    private final MilvusVectorService milvusVectorService;
    private final ChatCacheService chatCacheService;
    private final DocumentStreamService documentStreamService;
    private final UserAccountRepository userAccountRepository;

    public DocumentService(AppProperties properties,
                           DocumentRecordRepository documentRecordRepository,
                           DocumentProcessingService documentProcessingService,
                           MilvusVectorService milvusVectorService,
                           ChatCacheService chatCacheService,
                           DocumentStreamService documentStreamService,
                           UserAccountRepository userAccountRepository) {
        this.properties = properties;
        this.documentRecordRepository = documentRecordRepository;
        this.documentProcessingService = documentProcessingService;
        this.milvusVectorService = milvusVectorService;
        this.chatCacheService = chatCacheService;
        this.documentStreamService = documentStreamService;
        this.userAccountRepository = userAccountRepository;
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
        documentStreamService.publishDocumentChanged(documentRecord, "uploaded");
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
        return toResponses(records);
    }

    public PageResponse<DocumentItemResponse> listDocuments(String keyword, int page, int size) {
        Pageable pageable = PageRequest.of(Math.max(page, 0), sanitizePageSize(size), Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<DocumentRecord> resultPage;
        if (SecurityUtils.isAdmin()) {
            resultPage = normalizeKeyword(keyword).isBlank()
                    ? documentRecordRepository.findAll(pageable)
                    : documentRecordRepository.searchAllForAdmin(normalizeKeyword(keyword), pageable);
        } else {
            resultPage = normalizeKeyword(keyword).isBlank()
                    ? documentRecordRepository.findByUserId(SecurityUtils.currentUser().userId(), pageable)
                    : documentRecordRepository.findByUserIdAndFileNameContainingIgnoreCase(SecurityUtils.currentUser().userId(), normalizeKeyword(keyword), pageable);
        }
        return new PageResponse<>(
                toResponses(resultPage.getContent()),
                resultPage.getTotalElements(),
                resultPage.getTotalPages(),
                resultPage.getNumber(),
                resultPage.getSize()
        );
    }

    public DocumentItemResponse retryDocument(Long documentId) {
        DocumentRecord record = loadAccessibleDocument(documentId);
        DocumentStatus status = record.getStatus() == null ? DocumentStatus.UPLOADED : record.getStatus();
        if (status != DocumentStatus.FAILED && status != DocumentStatus.UPLOADED) {
            throw new IllegalArgumentException("Only failed or pending documents can be retried");
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
        documentStreamService.publishDocumentChanged(record, "retried");
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
        documentStreamService.publishDocumentDeleted(record.getUserId(), record.getId());
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
        return toResponse(record, null);
    }

    private List<DocumentItemResponse> toResponses(List<DocumentRecord> records) {
        Map<Long, String> usernames = new HashMap<>();
        userAccountRepository.findAllById(records.stream().map(DocumentRecord::getUserId).distinct().toList())
                .forEach(user -> usernames.put(user.getId(), user.getUsername()));
        return records.stream()
                .map(record -> toResponse(record, usernames.get(record.getUserId())))
                .toList();
    }

    private DocumentItemResponse toResponse(DocumentRecord record, String ownerUsername) {
        DocumentStatus status = record.getStatus() == null ? inferLegacyStatus(record) : record.getStatus();
        return new DocumentItemResponse(
                record.getId(),
                record.getFileName(),
                record.getUserId(),
                ownerUsername,
                status.name(),
                progressOf(status),
                record.getChunkCount(),
                record.getErrorMessage(),
                record.getCreatedAt(),
                record.getUpdatedAt() == null ? record.getCreatedAt() : record.getUpdatedAt()
        );
    }

    private String normalizeKeyword(String keyword) {
        return keyword == null ? "" : keyword.trim();
    }

    private int sanitizePageSize(int size) {
        if (size <= 0) {
            return 10;
        }
        return Math.min(size, 50);
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
