package com.example.aikb.service;

import com.example.aikb.agent.AgentService;
import com.example.aikb.dto.ChatRequest;
import com.example.aikb.dto.ChatResponse;
import com.example.aikb.dto.ChatStreamChunkResponse;
import com.example.aikb.dto.ChatStreamCompleteResponse;
import com.example.aikb.dto.ChatStreamErrorResponse;
import com.example.aikb.dto.ChatStreamMetadataResponse;
import com.example.aikb.entity.ChatMessage;
import com.example.aikb.entity.ChatSession;
import com.example.aikb.security.UserPrincipal;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;

@Service
public class ChatService {

    private final RagService ragService;
    private final AgentService agentService;
    private final ConversationService conversationService;

    public ChatService(RagService ragService,
                       AgentService agentService,
                       ConversationService conversationService) {
        this.ragService = ragService;
        this.agentService = agentService;
        this.conversationService = conversationService;
    }

    public ChatResponse chat(UserPrincipal principal, ChatRequest request) {
        AgentService.AgentResult directToolResult = agentService.tryDirectAnswer(principal, request.question());
        if (directToolResult != null) {
            ChatSession session = loadOrCreateSession(principal, request);
            conversationService.appendUserMessage(session, principal.userId(), request.question());
            ChatMessage assistantMessage = conversationService.appendAssistantMessage(
                    session,
                    principal.userId(),
                    directToolResult.answer(),
                    java.util.List.of(),
                    directToolResult.toolUsed()
            );
            return new ChatResponse(
                    session.getId(),
                    assistantMessage.getId(),
                    directToolResult.answer(),
                    java.util.List.of(),
                    directToolResult.toolUsed()
            );
        }

        PreparedChat preparedChat = prepareChat(principal, request);
        AgentService.AgentResult agentResult = agentService.ask(
                principal,
                request.question(),
                preparedChat.ragResult().context(),
                preparedChat.conversationHistory()
        );
        conversationService.appendUserMessage(preparedChat.session(), principal.userId(), request.question());
        ChatMessage assistantMessage = conversationService.appendAssistantMessage(
                preparedChat.session(),
                principal.userId(),
                agentResult.answer(),
                agentResult.directToolOnly() ? java.util.List.of() : preparedChat.ragResult().sources(),
                agentResult.toolUsed()
        );
        return new ChatResponse(
                preparedChat.session().getId(),
                assistantMessage.getId(),
                agentResult.answer(),
                agentResult.directToolOnly() ? java.util.List.of() : preparedChat.ragResult().sources(),
                agentResult.toolUsed()
        );
    }

    public void streamChat(UserPrincipal principal, ChatRequest request, SseEmitter emitter) {
        try {
            AgentService.AgentResult directToolResult = agentService.tryDirectAnswer(principal, request.question());
            if (directToolResult != null) {
                ChatSession session = loadOrCreateSession(principal, request);
                conversationService.appendUserMessage(session, principal.userId(), request.question());
                sendEvent(emitter, "metadata", new ChatStreamMetadataResponse(session.getId()));
                ChatMessage assistantMessage = conversationService.appendAssistantMessage(
                        session,
                        principal.userId(),
                        directToolResult.answer(),
                        java.util.List.of(),
                        directToolResult.toolUsed()
                );
                sendEvent(emitter, "complete", new ChatStreamCompleteResponse(
                        session.getId(),
                        assistantMessage.getId(),
                        directToolResult.answer(),
                        java.util.List.of(),
                        directToolResult.toolUsed(),
                        assistantMessage.getCreatedAt()
                ));
                emitter.complete();
                return;
            }

            PreparedChat preparedChat = prepareChat(principal, request);
            conversationService.appendUserMessage(preparedChat.session(), principal.userId(), request.question());
            sendEvent(emitter, "metadata", new ChatStreamMetadataResponse(preparedChat.session().getId()));
            agentService.stream(
                    principal,
                    request.question(),
                    preparedChat.ragResult().context(),
                    preparedChat.conversationHistory(),
                    delta -> sendEventSafely(emitter, "chunk", new ChatStreamChunkResponse(delta)),
                    agentResult -> {
                        try {
                            ChatMessage assistantMessage = conversationService.appendAssistantMessage(
                                    preparedChat.session(),
                                    principal.userId(),
                                    agentResult.answer(),
                                    agentResult.directToolOnly() ? java.util.List.of() : preparedChat.ragResult().sources(),
                                    agentResult.toolUsed()
                            );
                            sendEvent(emitter, "complete", new ChatStreamCompleteResponse(
                                    preparedChat.session().getId(),
                                    assistantMessage.getId(),
                                    agentResult.answer(),
                                    agentResult.directToolOnly() ? java.util.List.of() : preparedChat.ragResult().sources(),
                                    agentResult.toolUsed(),
                                    assistantMessage.getCreatedAt()
                            ));
                            emitter.complete();
                        } catch (Exception exception) {
                            sendErrorAndComplete(emitter, exception);
                        }
                    },
                    error -> sendErrorAndComplete(emitter, error)
            );
        } catch (Exception exception) {
            sendErrorAndComplete(emitter, exception);
        }
    }

    private PreparedChat prepareChat(UserPrincipal principal, ChatRequest request) {
        ChatSession session = request.sessionId() == null ? null : conversationService.loadAccessibleSession(principal, request.sessionId());
        String conversationHistory = conversationService.buildConversationContext(session);
        RagService.RagResult ragResult = ragService.retrieve(
                principal.userId(),
                "ADMIN".equalsIgnoreCase(principal.role()),
                request.question()
        );
        session = session == null ? conversationService.createSession(principal.userId(), request.question()) : conversationService.touchSession(session);
        return new PreparedChat(session, conversationHistory, ragResult);
    }

    private ChatSession loadOrCreateSession(UserPrincipal principal, ChatRequest request) {
        ChatSession session = request.sessionId() == null ? null : conversationService.loadAccessibleSession(principal, request.sessionId());
        return session == null ? conversationService.createSession(principal.userId(), request.question()) : conversationService.touchSession(session);
    }

    private void sendErrorAndComplete(SseEmitter emitter, Throwable error) {
        try {
            sendEvent(emitter, "error", new ChatStreamErrorResponse(error.getMessage() == null ? "流式生成失败" : error.getMessage()));
            emitter.complete();
        } catch (Exception ignored) {
            emitter.completeWithError(error);
        }
    }

    private void sendEventSafely(SseEmitter emitter, String eventName, Object payload) {
        try {
            sendEvent(emitter, eventName, payload);
        } catch (Exception exception) {
            emitter.completeWithError(exception);
        }
    }

    private void sendEvent(SseEmitter emitter, String eventName, Object payload) throws IOException {
        emitter.send(SseEmitter.event().name(eventName).data(payload));
    }

    private record PreparedChat(ChatSession session,
                                String conversationHistory,
                                RagService.RagResult ragResult) {
    }
}
