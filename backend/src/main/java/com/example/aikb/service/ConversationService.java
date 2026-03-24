package com.example.aikb.service;

import com.example.aikb.dto.ChatMessageResponse;
import com.example.aikb.dto.ChatSessionDetailResponse;
import com.example.aikb.dto.ChatSessionSummaryResponse;
import com.example.aikb.dto.PageResponse;
import com.example.aikb.dto.SourceItem;
import com.example.aikb.entity.ChatMessage;
import com.example.aikb.entity.ChatMessageRole;
import com.example.aikb.entity.ChatSession;
import com.example.aikb.repository.ChatMessageRepository;
import com.example.aikb.repository.ChatSessionRepository;
import com.example.aikb.security.UserPrincipal;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Service
public class ConversationService {

    private static final int SESSION_TITLE_LIMIT = 48;
    private static final int HISTORY_MESSAGE_LIMIT = 8;

    private final ChatSessionRepository chatSessionRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final ObjectMapper objectMapper;

    public ConversationService(ChatSessionRepository chatSessionRepository,
                               ChatMessageRepository chatMessageRepository,
                               ObjectMapper objectMapper) {
        this.chatSessionRepository = chatSessionRepository;
        this.chatMessageRepository = chatMessageRepository;
        this.objectMapper = objectMapper;
    }

    public PageResponse<ChatSessionSummaryResponse> listSessions(UserPrincipal principal, String keyword, int page, int size) {
        Pageable pageable = PageRequest.of(
                Math.max(page, 0),
                sanitizePageSize(size, 12),
                Sort.by(Sort.Direction.DESC, "updatedAt")
        );
        Page<ChatSession> sessionPage = normalizeKeyword(keyword).isBlank()
                ? chatSessionRepository.findByUserId(principal.userId(), pageable)
                : chatSessionRepository.findByUserIdAndTitleContainingIgnoreCase(principal.userId(), normalizeKeyword(keyword), pageable);
        return new PageResponse<>(
                sessionPage.getContent().stream().map(this::toSummary).toList(),
                sessionPage.getTotalElements(),
                sessionPage.getTotalPages(),
                sessionPage.getNumber(),
                sessionPage.getSize()
        );
    }

    public ChatSessionDetailResponse getSessionDetail(UserPrincipal principal, Long sessionId) {
        ChatSession session = loadAccessibleSession(principal, sessionId);
        List<ChatMessageResponse> messages = chatMessageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId).stream()
                .map(this::toMessageResponse)
                .toList();
        return new ChatSessionDetailResponse(
                session.getId(),
                session.getTitle(),
                session.getCreatedAt(),
                session.getUpdatedAt(),
                messages
        );
    }

    public ChatSession loadAccessibleSession(UserPrincipal principal, Long sessionId) {
        return chatSessionRepository.findByIdAndUserId(sessionId, principal.userId())
                .orElseThrow(() -> new IllegalArgumentException("Chat session not found"));
    }

    @Transactional
    public ChatSessionSummaryResponse renameSession(UserPrincipal principal, Long sessionId, String title) {
        ChatSession session = loadAccessibleSession(principal, sessionId);
        String normalizedTitle = normalizeSessionTitle(title);
        if (normalizedTitle.isBlank()) {
            throw new IllegalArgumentException("Session title cannot be blank");
        }
        session.setTitle(normalizedTitle);
        session.setUpdatedAt(LocalDateTime.now());
        return toSummary(chatSessionRepository.save(session));
    }

    @Transactional
    public void deleteSession(UserPrincipal principal, Long sessionId) {
        ChatSession session = loadAccessibleSession(principal, sessionId);
        chatMessageRepository.deleteBySessionId(session.getId());
        chatSessionRepository.delete(session);
    }

    public ChatSession createSession(Long userId, String firstQuestion) {
        ChatSession session = new ChatSession();
        session.setUserId(userId);
        session.setTitle(buildSessionTitle(firstQuestion));
        session.setCreatedAt(LocalDateTime.now());
        session.setUpdatedAt(session.getCreatedAt());
        return chatSessionRepository.save(session);
    }

    public ChatSession touchSession(ChatSession session) {
        session.setUpdatedAt(LocalDateTime.now());
        return chatSessionRepository.save(session);
    }

    public void appendUserMessage(ChatSession session, Long userId, String content) {
        saveMessage(session.getId(), userId, ChatMessageRole.USER, content, List.of(), null);
    }

    public ChatMessage appendAssistantMessage(ChatSession session, Long userId, String content, List<SourceItem> sources, String toolUsed) {
        return saveMessage(session.getId(), userId, ChatMessageRole.ASSISTANT, content, sources, toolUsed);
    }

    public String buildConversationContext(ChatSession session) {
        if (session == null) {
            return "无历史对话。";
        }
        List<ChatMessage> recentMessages = new ArrayList<>(chatMessageRepository.findTop8BySessionIdOrderByCreatedAtDesc(session.getId()));
        Collections.reverse(recentMessages);
        if (recentMessages.isEmpty()) {
            return "无历史对话。";
        }
        return recentMessages.stream()
                .limit(HISTORY_MESSAGE_LIMIT)
                .map(message -> "[%s] %s".formatted(message.getRole().name(), message.getContent()))
                .reduce((left, right) -> left + "\n" + right)
                .orElse("无历史对话。");
    }

    private ChatMessage saveMessage(Long sessionId, Long userId, ChatMessageRole role, String content, List<SourceItem> sources, String toolUsed) {
        ChatMessage message = new ChatMessage();
        message.setSessionId(sessionId);
        message.setUserId(userId);
        message.setRole(role);
        message.setContent(content);
        message.setSourcesJson(serializeSources(sources));
        message.setToolUsed(toolUsed);
        message.setCreatedAt(LocalDateTime.now());
        return chatMessageRepository.save(message);
    }

    private ChatSessionSummaryResponse toSummary(ChatSession session) {
        return new ChatSessionSummaryResponse(
                session.getId(),
                session.getTitle(),
                session.getCreatedAt(),
                session.getUpdatedAt()
        );
    }

    private ChatMessageResponse toMessageResponse(ChatMessage message) {
        return new ChatMessageResponse(
                message.getId(),
                message.getRole().name(),
                message.getContent(),
                deserializeSources(message.getSourcesJson()),
                message.getToolUsed(),
                message.getCreatedAt()
        );
    }

    private String buildSessionTitle(String question) {
        String normalized = normalizeSessionTitle(question);
        if (normalized.isBlank()) {
            return "新会话";
        }
        if (normalized.length() <= SESSION_TITLE_LIMIT) {
            return normalized;
        }
        return normalized.substring(0, SESSION_TITLE_LIMIT) + "...";
    }

    private String normalizeSessionTitle(String title) {
        return title == null ? "" : title.trim().replaceAll("\\s+", " ");
    }

    private int sanitizePageSize(int requestedSize, int defaultSize) {
        if (requestedSize <= 0) {
            return defaultSize;
        }
        return Math.min(requestedSize, 50);
    }

    private String normalizeKeyword(String keyword) {
        return keyword == null ? "" : keyword.trim();
    }

    private String serializeSources(List<SourceItem> sources) {
        try {
            return objectMapper.writeValueAsString(sources == null ? List.of() : sources);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to serialize chat sources", exception);
        }
    }

    private List<SourceItem> deserializeSources(String sourcesJson) {
        if (sourcesJson == null || sourcesJson.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(sourcesJson, new TypeReference<>() {
            });
        } catch (Exception exception) {
            return List.of();
        }
    }
}
