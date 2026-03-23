package com.example.aikb.service;

import com.example.aikb.entity.DocumentRecord;
import com.example.aikb.entity.DocumentStatus;
import com.example.aikb.security.UserPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThatCode;

class DocumentStreamServiceTest {

    @Test
    void subscribeAndPublishShouldNotThrow() {
        DocumentStreamService service = new DocumentStreamService();
        var userEmitter = service.subscribe(new UserPrincipal(5L, "user", "USER"));
        var adminEmitter = service.subscribe(new UserPrincipal(1L, "admin", "ADMIN"));

        DocumentRecord record = new DocumentRecord();
        record.setId(11L);
        record.setUserId(5L);
        record.setStatus(DocumentStatus.EMBEDDING);
        record.setUpdatedAt(LocalDateTime.now());

        assertThatCode(() -> {
            service.publishDocumentChanged(record, "updated");
            service.publishDocumentDeleted(5L, 11L);
        }).doesNotThrowAnyException();

        userEmitter.complete();
        adminEmitter.complete();
    }

    @Test
    void publishShouldIgnoreBrokenEmitter() {
        DocumentStreamService service = new DocumentStreamService();
        service.subscribe(new UserPrincipal(5L, "user", "USER"), new BrokenEmitter());

        DocumentRecord record = new DocumentRecord();
        record.setId(12L);
        record.setUserId(5L);
        record.setStatus(DocumentStatus.UPLOADED);
        record.setUpdatedAt(LocalDateTime.now());

        assertThatCode(() -> service.publishDocumentChanged(record, "uploaded"))
                .doesNotThrowAnyException();
    }

    private static final class BrokenEmitter extends SseEmitter {

        @Override
        public void send(SseEventBuilder builder) {
            throw new IllegalStateException("broken connection");
        }
    }
}
