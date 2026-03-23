package com.example.aikb.controller;

import com.example.aikb.dto.ChatRequest;
import com.example.aikb.dto.ChatResponse;
import com.example.aikb.dto.ChatSessionDetailResponse;
import com.example.aikb.dto.ChatSessionSummaryResponse;
import com.example.aikb.dto.PageResponse;
import com.example.aikb.service.ChatService;
import com.example.aikb.service.ConversationService;
import com.example.aikb.util.SecurityUtils;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/chat")
public class ChatController {

    private final ChatService chatService;
    private final ConversationService conversationService;

    public ChatController(ChatService chatService, ConversationService conversationService) {
        this.chatService = chatService;
        this.conversationService = conversationService;
    }

    @PostMapping("/ask")
    public ChatResponse ask(@Valid @RequestBody ChatRequest request) {
        return chatService.chat(SecurityUtils.currentUser(), request);
    }

    @GetMapping("/sessions")
    public PageResponse<ChatSessionSummaryResponse> sessions(@RequestParam(defaultValue = "") String keyword,
                                                             @RequestParam(defaultValue = "0") int page,
                                                             @RequestParam(defaultValue = "10") int size) {
        return conversationService.listSessions(SecurityUtils.currentUser(), keyword, page, size);
    }

    @GetMapping("/sessions/{sessionId}")
    public ChatSessionDetailResponse sessionDetail(@PathVariable Long sessionId) {
        return conversationService.getSessionDetail(SecurityUtils.currentUser(), sessionId);
    }
}
