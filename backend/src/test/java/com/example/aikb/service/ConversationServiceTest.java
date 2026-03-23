package com.example.aikb.service;

import com.example.aikb.dto.ChatSessionDetailResponse;
import com.example.aikb.dto.PageResponse;
import com.example.aikb.dto.SourceItem;
import com.example.aikb.entity.ChatMessage;
import com.example.aikb.entity.ChatMessageRole;
import com.example.aikb.entity.ChatSession;
import com.example.aikb.repository.ChatMessageRepository;
import com.example.aikb.repository.ChatSessionRepository;
import com.example.aikb.security.UserPrincipal;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConversationServiceTest {

    @Mock
    private ChatSessionRepository chatSessionRepository;

    @Mock
    private ChatMessageRepository chatMessageRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void listSessionsShouldUseKeywordQueryAndMapPageMetadata() {
        ConversationService service = new ConversationService(chatSessionRepository, chatMessageRepository, objectMapper);
        ChatSession session = new ChatSession();
        session.setId(10L);
        session.setUserId(7L);
        session.setTitle("制度问答");
        session.setCreatedAt(LocalDateTime.now().minusDays(1));
        session.setUpdatedAt(LocalDateTime.now());

        when(chatSessionRepository.findByUserIdAndTitleContainingIgnoreCase(eq(7L), eq("制度"), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(session), PageRequest.of(0, 12), 1));

        PageResponse<?> response = service.listSessions(new UserPrincipal(7L, "user", "USER"), "  制度 ", 0, 0);

        assertThat(response.items()).hasSize(1);
        assertThat(response.totalElements()).isEqualTo(1);
        assertThat(response.size()).isEqualTo(12);
        verify(chatSessionRepository).findByUserIdAndTitleContainingIgnoreCase(eq(7L), eq("制度"), any(Pageable.class));
    }

    @Test
    void getSessionDetailShouldDeserializeStoredSources() throws Exception {
        ConversationService service = new ConversationService(chatSessionRepository, chatMessageRepository, objectMapper);
        ChatSession session = new ChatSession();
        session.setId(15L);
        session.setUserId(7L);
        session.setTitle("报销制度");
        session.setCreatedAt(LocalDateTime.now().minusHours(2));
        session.setUpdatedAt(LocalDateTime.now());

        ChatMessage userMessage = new ChatMessage();
        userMessage.setId(1L);
        userMessage.setSessionId(15L);
        userMessage.setRole(ChatMessageRole.USER);
        userMessage.setContent("总结制度");
        userMessage.setSourcesJson("");
        userMessage.setCreatedAt(LocalDateTime.now().minusMinutes(2));

        ChatMessage assistantMessage = new ChatMessage();
        assistantMessage.setId(2L);
        assistantMessage.setSessionId(15L);
        assistantMessage.setRole(ChatMessageRole.ASSISTANT);
        assistantMessage.setContent("这是总结");
        assistantMessage.setSourcesJson(objectMapper.writeValueAsString(List.of(new SourceItem("doc.txt", "chunk-1", "报销制度原文"))));
        assistantMessage.setToolUsed("documentCount");
        assistantMessage.setCreatedAt(LocalDateTime.now().minusMinutes(1));

        when(chatSessionRepository.findByIdAndUserId(15L, 7L)).thenReturn(Optional.of(session));
        when(chatMessageRepository.findBySessionIdOrderByCreatedAtAsc(15L)).thenReturn(List.of(userMessage, assistantMessage));

        ChatSessionDetailResponse response = service.getSessionDetail(new UserPrincipal(7L, "user", "USER"), 15L);

        assertThat(response.title()).isEqualTo("报销制度");
        assertThat(response.messages()).hasSize(2);
        assertThat(response.messages().get(1).sources()).hasSize(1);
        assertThat(response.messages().get(1).toolUsed()).isEqualTo("documentCount");
    }

    @Test
    void buildConversationContextShouldReturnOrderedHistoryAndFallback() {
        ConversationService service = new ConversationService(chatSessionRepository, chatMessageRepository, objectMapper);

        assertThat(service.buildConversationContext(null)).isEqualTo("无历史对话。");

        ChatSession session = new ChatSession();
        session.setId(20L);

        ChatMessage latest = new ChatMessage();
        latest.setRole(ChatMessageRole.ASSISTANT);
        latest.setContent("第二条回答");

        ChatMessage earlier = new ChatMessage();
        earlier.setRole(ChatMessageRole.USER);
        earlier.setContent("第一条提问");

        when(chatMessageRepository.findTop8BySessionIdOrderByCreatedAtDesc(20L)).thenReturn(List.of(latest, earlier));

        String context = service.buildConversationContext(session);

        assertThat(context).isEqualTo("[USER] 第一条提问\n[ASSISTANT] 第二条回答");
    }

    @Test
    void createAndAppendMessagesShouldPersistSessionAndSerializedSources() {
        ConversationService service = new ConversationService(chatSessionRepository, chatMessageRepository, objectMapper);
        when(chatSessionRepository.save(any(ChatSession.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(chatMessageRepository.save(any(ChatMessage.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ChatSession session = service.createSession(9L, "   " + "超长标题".repeat(20) + "   ");
        service.touchSession(session);
        service.appendUserMessage(session, 9L, "继续分析");
        service.appendAssistantMessage(session, 9L, "好的", List.of(new SourceItem("doc.txt", "chunk-2", "内容")), "systemStatus");

        ArgumentCaptor<ChatSession> sessionCaptor = ArgumentCaptor.forClass(ChatSession.class);
        verify(chatSessionRepository, org.mockito.Mockito.atLeastOnce()).save(sessionCaptor.capture());
        assertThat(sessionCaptor.getAllValues().get(0).getTitle()).endsWith("...");
        assertThat(sessionCaptor.getAllValues().get(0).getTitle().length()).isLessThanOrEqualTo(51);

        ArgumentCaptor<ChatMessage> messageCaptor = ArgumentCaptor.forClass(ChatMessage.class);
        verify(chatMessageRepository, org.mockito.Mockito.times(2)).save(messageCaptor.capture());
        assertThat(messageCaptor.getAllValues().get(0).getRole()).isEqualTo(ChatMessageRole.USER);
        assertThat(messageCaptor.getAllValues().get(1).getSourcesJson()).contains("doc.txt");
        assertThat(messageCaptor.getAllValues().get(1).getToolUsed()).isEqualTo("systemStatus");
    }

    @Test
    void loadAccessibleSessionShouldRejectUnknownSession() {
        ConversationService service = new ConversationService(chatSessionRepository, chatMessageRepository, objectMapper);
        when(chatSessionRepository.findByIdAndUserId(99L, 7L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.loadAccessibleSession(new UserPrincipal(7L, "user", "USER"), 99L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Chat session not found");
    }
}
