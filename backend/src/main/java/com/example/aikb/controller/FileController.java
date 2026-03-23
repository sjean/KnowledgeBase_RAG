package com.example.aikb.controller;

import com.example.aikb.dto.DocumentItemResponse;
import com.example.aikb.dto.UploadResponse;
import com.example.aikb.service.DocumentService;
import com.example.aikb.util.SecurityUtils;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/file")
public class FileController {

    private final DocumentService documentService;

    public FileController(DocumentService documentService) {
        this.documentService = documentService;
    }

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public UploadResponse upload(@RequestPart("file") MultipartFile file) {
        return documentService.upload(SecurityUtils.currentUser().userId(), file);
    }

    @GetMapping("/documents")
    public List<DocumentItemResponse> listDocuments() {
        return documentService.listDocuments();
    }

    @PostMapping("/documents/{documentId}/retry")
    public DocumentItemResponse retryDocument(@PathVariable Long documentId) {
        return documentService.retryDocument(documentId);
    }

    @DeleteMapping("/documents/{documentId}")
    public Map<String, String> deleteDocument(@PathVariable Long documentId) {
        documentService.deleteDocument(documentId);
        return Map.of("message", "Document deleted");
    }
}
