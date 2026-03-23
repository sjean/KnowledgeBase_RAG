package com.example.aikb.service;

import com.example.aikb.entity.DocumentRecord;
import com.example.aikb.security.UserPrincipal;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class DocumentStreamService {

    private static final Long SSE_TIMEOUT_MS = 30L * 60L * 1000L;

    private final ConcurrentHashMap<String, Subscription> subscriptions = new ConcurrentHashMap<>();

    public SseEmitter subscribe(UserPrincipal principal) {
        return subscribe(principal, new SseEmitter(SSE_TIMEOUT_MS));
    }

    SseEmitter subscribe(UserPrincipal principal, SseEmitter emitter) {
        String subscriptionId = UUID.randomUUID().toString();
        Subscription subscription = new Subscription(subscriptionId, principal.userId(), "ADMIN".equalsIgnoreCase(principal.role()), emitter);
        subscriptions.put(subscriptionId, subscription);
        emitter.onCompletion(() -> subscriptions.remove(subscriptionId));
        emitter.onTimeout(() -> subscriptions.remove(subscriptionId));
        emitter.onError(error -> subscriptions.remove(subscriptionId));
        sendEvent(subscription, "connected", Map.of("connectedAt", LocalDateTime.now().toString()));
        return emitter;
    }

    public void publishDocumentChanged(DocumentRecord record, String changeType) {
        Map<String, Object> payload = Map.of(
                "type", changeType,
                "documentId", record.getId(),
                "userId", record.getUserId(),
                "status", record.getStatus() == null ? "UNKNOWN" : record.getStatus().name(),
                "updatedAt", record.getUpdatedAt() == null ? "" : record.getUpdatedAt().toString()
        );
        publishToRelevantUsers(record.getUserId(), "document-change", payload);
    }

    public void publishDocumentDeleted(Long ownerUserId, Long documentId) {
        publishToRelevantUsers(ownerUserId, "document-change", Map.of(
                "type", "deleted",
                "documentId", documentId,
                "userId", ownerUserId
        ));
    }

    private void publishToRelevantUsers(Long ownerUserId, String eventName, Map<String, Object> payload) {
        subscriptions.values().forEach(subscription -> {
            if (subscription.admin() || subscription.userId().equals(ownerUserId)) {
                sendEvent(subscription, eventName, payload);
            }
        });
    }

    private void sendEvent(Subscription subscription, String eventName, Object payload) {
        try {
            subscription.emitter().send(SseEmitter.event()
                    .name(eventName)
                    .data(payload));
        } catch (Exception exception) {
            subscriptions.remove(subscription.id());
            try {
                subscription.emitter().complete();
            } catch (Exception ignored) {
                // Ignore secondary errors from already-broken client connections.
            }
        }
    }

    private record Subscription(String id, Long userId, boolean admin, SseEmitter emitter) {
    }
}
