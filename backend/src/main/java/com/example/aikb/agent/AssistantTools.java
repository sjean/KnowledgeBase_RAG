package com.example.aikb.agent;

import com.example.aikb.service.DocumentService;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

import java.lang.management.ManagementFactory;
import java.time.LocalDateTime;

@Component
public class AssistantTools {

    private final DocumentService documentService;

    public AssistantTools(DocumentService documentService) {
        this.documentService = documentService;
    }

    @Tool("Query the current user's document count from the database")
    public String queryCurrentUserDocumentCount() {
        ToolTrackingHolder.set("queryCurrentUserDocumentCount");
        long count = documentService.countByUser(AgentExecutionContextHolder.userId());
        return "当前用户文档数量为 " + count + "。";
    }

    @Tool("Query total document count for administrators")
    public String queryTotalDocumentCount() {
        ToolTrackingHolder.set("queryTotalDocumentCount");
        return "当前系统文档总数为 " + documentService.countAll() + "。";
    }

    @Tool("Return current system status information")
    public String currentSystemStatus() {
        ToolTrackingHolder.set("currentSystemStatus");
        Runtime runtime = Runtime.getRuntime();
        long usedMemoryMb = (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024;
        long uptimeSeconds = ManagementFactory.getRuntimeMXBean().getUptime() / 1000;
        return "System time: " + LocalDateTime.now()
                + ", uptimeSeconds=" + uptimeSeconds
                + ", usedMemoryMb=" + usedMemoryMb
                + ", javaVersion=" + System.getProperty("java.version");
    }
}
