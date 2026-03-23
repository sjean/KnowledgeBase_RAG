package com.example.aikb.agent;

import com.example.aikb.service.DocumentService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AssistantToolsTest {

    @Mock
    private DocumentService documentService;

    @AfterEach
    void tearDown() {
        ToolTrackingHolder.clear();
    }

    @Test
    void queryCurrentUserDocumentCountShouldUseServiceAndTrackTool() {
        when(documentService.countByUser(3L)).thenReturn(5L);
        AssistantTools assistantTools = new AssistantTools(documentService);

        String result = assistantTools.queryCurrentUserDocumentCount(3L);

        assertThat(result).isEqualTo("Current user document count: 5");
        assertThat(ToolTrackingHolder.get()).isEqualTo("queryCurrentUserDocumentCount");
    }

    @Test
    void queryTotalDocumentCountShouldUseServiceAndTrackTool() {
        when(documentService.countAll()).thenReturn(12L);
        AssistantTools assistantTools = new AssistantTools(documentService);

        String result = assistantTools.queryTotalDocumentCount();

        assertThat(result).isEqualTo("Total document count: 12");
        assertThat(ToolTrackingHolder.get()).isEqualTo("queryTotalDocumentCount");
    }

    @Test
    void currentSystemStatusShouldReturnSummaryAndTrackTool() {
        AssistantTools assistantTools = new AssistantTools(documentService);

        String result = assistantTools.currentSystemStatus();

        assertThat(result).contains("System time:");
        assertThat(result).contains("uptimeSeconds=");
        assertThat(result).contains("usedMemoryMb=");
        assertThat(result).contains("javaVersion=");
        assertThat(ToolTrackingHolder.get()).isEqualTo("currentSystemStatus");
    }
}
