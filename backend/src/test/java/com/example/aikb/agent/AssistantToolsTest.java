package com.example.aikb.agent;

import com.example.aikb.service.DocumentService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AssistantToolsTest {

    @AfterEach
    void tearDown() {
        ToolTrackingHolder.clear();
        AgentExecutionContextHolder.clear();
    }

    @Test
    void queryCurrentUserDocumentCountShouldUseServiceAndTrackTool() {
        AssistantTools assistantTools = new AssistantTools(new StubDocumentService(5L, 12L));
        AgentExecutionContextHolder.set(3L, "USER");

        String result = assistantTools.queryCurrentUserDocumentCount();

        assertThat(result).isEqualTo("当前用户文档数量为 5。");
        assertThat(ToolTrackingHolder.get()).isEqualTo("queryCurrentUserDocumentCount");
    }

    @Test
    void queryTotalDocumentCountShouldUseServiceAndTrackTool() {
        AssistantTools assistantTools = new AssistantTools(new StubDocumentService(5L, 12L));

        String result = assistantTools.queryTotalDocumentCount();

        assertThat(result).isEqualTo("当前系统文档总数为 12。");
        assertThat(ToolTrackingHolder.get()).isEqualTo("queryTotalDocumentCount");
    }

    @Test
    void currentSystemStatusShouldReturnSummaryAndTrackTool() {
        AssistantTools assistantTools = new AssistantTools(new StubDocumentService(5L, 12L));

        String result = assistantTools.currentSystemStatus();

        assertThat(result).contains("System time:");
        assertThat(result).contains("uptimeSeconds=");
        assertThat(result).contains("usedMemoryMb=");
        assertThat(result).contains("javaVersion=");
        assertThat(ToolTrackingHolder.get()).isEqualTo("currentSystemStatus");
    }

    private static class StubDocumentService extends DocumentService {

        private final long userCount;
        private final long totalCount;

        private StubDocumentService(long userCount, long totalCount) {
            super(null, null, null, null, null, null, null);
            this.userCount = userCount;
            this.totalCount = totalCount;
        }

        @Override
        public long countByUser(Long userId) {
            return userCount;
        }

        @Override
        public long countAll() {
            return totalCount;
        }
    }
}
