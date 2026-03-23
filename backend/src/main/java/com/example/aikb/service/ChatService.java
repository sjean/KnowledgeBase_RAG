package com.example.aikb.service;

import com.example.aikb.agent.AgentService;
import com.example.aikb.dto.ChatRequest;
import com.example.aikb.dto.ChatResponse;
import com.example.aikb.entity.ChatMessage;
import com.example.aikb.entity.ChatSession;
import com.example.aikb.security.UserPrincipal;
import org.springframework.stereotype.Service;

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
        ChatSession session = request.sessionId() == null ? null : conversationService.loadAccessibleSession(principal, request.sessionId());
        String conversationHistory = conversationService.buildConversationContext(session);
        RagService.RagResult ragResult = ragService.retrieve(
                principal.userId(),
                "ADMIN".equalsIgnoreCase(principal.role()),
                request.question()
        );
        AgentService.AgentResult agentResult = agentService.ask(principal, request.question(), ragResult.context(), conversationHistory);
        if (session == null) {
            session = conversationService.createSession(principal.userId(), request.question());
        } else {
            conversationService.touchSession(session);
        }
        conversationService.appendUserMessage(session, principal.userId(), request.question());
        ChatMessage assistantMessage = conversationService.appendAssistantMessage(
                session,
                principal.userId(),
                agentResult.answer(),
                ragResult.sources(),
                agentResult.toolUsed()
        );
        return new ChatResponse(
                session.getId(),
                assistantMessage.getId(),
                agentResult.answer(),
                ragResult.sources(),
                agentResult.toolUsed()
        );
    }
}
