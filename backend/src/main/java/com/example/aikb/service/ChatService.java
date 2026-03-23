package com.example.aikb.service;

import com.example.aikb.agent.AgentService;
import com.example.aikb.dto.ChatResponse;
import com.example.aikb.security.UserPrincipal;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

@Service
public class ChatService {

    private final RagService ragService;
    private final AgentService agentService;

    public ChatService(RagService ragService, AgentService agentService) {
        this.ragService = ragService;
        this.agentService = agentService;
    }

    @Cacheable(value = "chatAnswers", key = "#principal.userId() + ':' + #question")
    public ChatResponse chat(UserPrincipal principal, String question) {
        RagService.RagResult ragResult = ragService.retrieve(
                principal.userId(),
                "ADMIN".equalsIgnoreCase(principal.role()),
                question
        );
        AgentService.AgentResult agentResult = agentService.ask(principal, question, ragResult.context());
        return new ChatResponse(
                agentResult.answer(),
                ragResult.sources(),
                agentResult.toolUsed()
        );
    }
}
