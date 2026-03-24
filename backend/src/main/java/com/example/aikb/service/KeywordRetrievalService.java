package com.example.aikb.service;

import com.example.aikb.config.AppProperties;
import com.example.aikb.dto.SourceItem;
import com.example.aikb.entity.DocumentRecord;
import com.example.aikb.entity.DocumentStatus;
import com.example.aikb.repository.DocumentRecordRepository;
import com.example.aikb.util.TextChunker;
import com.example.aikb.util.TikaParserUtil;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class KeywordRetrievalService {

    private static final List<String> QUESTION_NOISE_TERMS = List.of(
            "请", "帮我", "帮忙", "一下", "总结一下", "总结", "概括", "介绍", "分析",
            "看下", "看一下", "说说", "讲讲", "给我", "这份", "这个", "该", "文档", "文件",
            "内容", "主要", "关于", "里面", "存在", "有个", "有一个", "一下子"
    );

    private final DocumentRecordRepository documentRecordRepository;
    private final TikaParserUtil tikaParserUtil;
    private final AppProperties properties;
    private final ConcurrentHashMap<String, List<String>> chunkCache = new ConcurrentHashMap<>();

    public KeywordRetrievalService(DocumentRecordRepository documentRecordRepository,
                                   TikaParserUtil tikaParserUtil,
                                   AppProperties properties) {
        this.documentRecordRepository = documentRecordRepository;
        this.tikaParserUtil = tikaParserUtil;
        this.properties = properties;
    }

    public List<SourceItem> search(Long userId, boolean admin, String question, List<String> preferredFileNames, int topK) {
        String normalizedQuestion = normalize(question);
        String queryCore = stripNoise(normalizedQuestion);
        if (queryCore.isBlank()) {
            return List.of();
        }

        List<DocumentRecord> candidateDocuments = loadCandidateDocuments(userId, admin, preferredFileNames);
        if (candidateDocuments.isEmpty()) {
            return List.of();
        }

        List<ScoredChunk> matches = new ArrayList<>();
        for (DocumentRecord document : candidateDocuments) {
            List<String> chunks = loadChunks(document);
            for (int index = 0; index < chunks.size(); index++) {
                String chunk = chunks.get(index);
                int score = scoreChunk(queryCore, document.getFileName(), chunk);
                if (score > 0) {
                    matches.add(new ScoredChunk(
                            new SourceItem(document.getFileName(), "kw-doc-" + document.getId() + "-" + index, chunk),
                            score
                    ));
                }
            }
        }

        return matches.stream()
                .sorted(Comparator.comparingInt(ScoredChunk::score).reversed())
                .map(ScoredChunk::source)
                .distinct()
                .limit(Math.max(topK, 1))
                .toList();
    }

    private List<DocumentRecord> loadCandidateDocuments(Long userId, boolean admin, List<String> preferredFileNames) {
        List<DocumentRecord> readyDocuments = admin
                ? documentRecordRepository.findByStatusOrderByCreatedAtDesc(DocumentStatus.READY)
                : documentRecordRepository.findByUserIdAndStatusOrderByCreatedAtDesc(userId, DocumentStatus.READY);

        if (preferredFileNames == null || preferredFileNames.isEmpty()) {
            return readyDocuments;
        }

        Set<String> preferred = new LinkedHashSet<>(preferredFileNames);
        List<DocumentRecord> filtered = readyDocuments.stream()
                .filter(document -> preferred.contains(document.getFileName()))
                .toList();
        return filtered.isEmpty() ? readyDocuments : filtered;
    }

    private List<String> loadChunks(DocumentRecord document) {
        String cacheKey = document.getStoragePath() + "::" + document.getUpdatedAt();
        return chunkCache.computeIfAbsent(cacheKey, key -> {
            String text = tikaParserUtil.parseText(Path.of(document.getStoragePath()));
            return TextChunker.split(text, properties.getRag().getChunkSize(), properties.getRag().getChunkOverlap());
        });
    }

    private int scoreChunk(String queryCore, String fileName, String chunk) {
        String normalizedChunk = normalize(chunk);
        String normalizedFileName = normalize(fileName);

        int score = 0;
        if (normalizedFileName.contains(queryCore)) {
            score += 120 + queryCore.length();
        }
        if (normalizedChunk.contains(queryCore)) {
            score += 100 + queryCore.length() * 2;
        }

        for (String term : buildSearchTerms(queryCore)) {
            if (term.length() < 2) {
                continue;
            }
            if (normalizedFileName.contains(term)) {
                score += 20 + term.length();
            }
            if (normalizedChunk.contains(term)) {
                score += 15 + term.length();
            }
        }

        return score;
    }

    private Set<String> buildSearchTerms(String queryCore) {
        Set<String> terms = new LinkedHashSet<>();
        terms.add(queryCore);
        if (queryCore.length() >= 4) {
            for (int i = 0; i < queryCore.length() - 1; i++) {
                String biGram = queryCore.substring(i, i + 2);
                if (biGram.length() == 2) {
                    terms.add(biGram);
                }
            }
        }
        return terms;
    }

    private String stripNoise(String text) {
        String result = text;
        for (String term : QUESTION_NOISE_TERMS) {
            result = result.replace(normalize(term), "");
        }
        return result;
    }

    private String normalize(String text) {
        return text == null ? "" : text
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\.[a-z0-9]+$", "")
                .replaceAll("[\\p{Punct}\\p{IsPunctuation}·•，。！？；：、“”‘’（）()【】\\[\\]<>《》\\-_/\\\\]+", "")
                .replaceAll("\\s+", "");
    }

    private record ScoredChunk(SourceItem source, int score) {
    }
}
