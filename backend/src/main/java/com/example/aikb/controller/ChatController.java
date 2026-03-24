package com.example.aikb.controller;

import com.example.aikb.dto.ChatRequest;
import com.example.aikb.dto.ChatResponse;
import com.example.aikb.dto.ChatSessionDetailResponse;
import com.example.aikb.dto.ChatSessionSummaryResponse;
import com.example.aikb.dto.PageResponse;
import com.example.aikb.dto.RenameSessionRequest;
import com.example.aikb.service.ChatService;
import com.example.aikb.service.ConversationService;
import com.example.aikb.util.SecurityUtils;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.concurrent.CompletableFuture;

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

    @PostMapping(path = "/ask/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter askStream(@Valid @RequestBody ChatRequest request) {
        SseEmitter emitter = new SseEmitter(0L);
        var principal = SecurityUtils.currentUser();
        CompletableFuture.runAsync(() -> chatService.streamChat(principal, request, emitter));
        return emitter;
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

    @PatchMapping("/sessions/{sessionId}")
    public ChatSessionSummaryResponse renameSession(@PathVariable Long sessionId,
                                                    @Valid @RequestBody RenameSessionRequest request) {
        return conversationService.renameSession(SecurityUtils.currentUser(), sessionId, request.title());
    }

    @DeleteMapping("/sessions/{sessionId}")
    public void deleteSession(@PathVariable Long sessionId) {
        conversationService.deleteSession(SecurityUtils.currentUser(), sessionId);
    }
}
