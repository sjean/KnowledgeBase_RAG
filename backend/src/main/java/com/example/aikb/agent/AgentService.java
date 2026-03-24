package com.example.aikb.agent;

import com.example.aikb.security.UserPrincipal;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.TokenStream;
import org.springframework.stereotype.Service;

import java.util.function.Consumer;

@Service
public class AgentService {

    private final KnowledgeAssistant knowledgeAssistant;
    private final StreamingKnowledgeAssistant streamingKnowledgeAssistant;
    private final AssistantTools assistantTools;

    public AgentService(OpenAiChatModel chatModel,
                        OpenAiStreamingChatModel streamingChatModel,
                        AssistantTools assistantTools) {
        this.assistantTools = assistantTools;
        this.knowledgeAssistant = AiServices.builder(KnowledgeAssistant.class)
                .chatLanguageModel(chatModel)
                .tools(assistantTools)
                .build();
        this.streamingKnowledgeAssistant = AiServices.builder(StreamingKnowledgeAssistant.class)
                .streamingChatLanguageModel(streamingChatModel)
                .tools(assistantTools)
                .build();
    }

    public AgentResult ask(UserPrincipal principal, String question, String context) {
        return ask(principal, question, context, "无历史对话。");
    }

    public AgentResult tryDirectAnswer(UserPrincipal principal, String question) {
        return answerWithDirectToolFallback(principal, question);
    }

    public AgentResult ask(UserPrincipal principal, String question, String context, String conversationHistory) {
        AgentResult forcedToolResult = tryDirectAnswer(principal, question);
        if (forcedToolResult != null) {
            return forcedToolResult;
        }

        AgentExecutionContextHolder.set(principal.userId(), principal.role());
        ToolTrackingHolder.clear();
        try {
            String answer = knowledgeAssistant.chat(buildPrompt(principal, question, context, conversationHistory));
            AgentResult result = new AgentResult(answer, ToolTrackingHolder.get(), false);
            ToolTrackingHolder.clear();
            return result;
        } finally {
            AgentExecutionContextHolder.clear();
            ToolTrackingHolder.clear();
        }
    }

    public void stream(UserPrincipal principal,
                       String question,
                       String context,
                       String conversationHistory,
                       Consumer<String> onNext,
                       Consumer<AgentResult> onComplete,
        Consumer<Throwable> onError) {
        AgentResult forcedToolResult = tryDirectAnswer(principal, question);
        if (forcedToolResult != null) {
            onComplete.accept(forcedToolResult);
            return;
        }

        AgentExecutionContextHolder.set(principal.userId(), principal.role());
        ToolTrackingHolder.clear();
        TokenStream tokenStream = streamingKnowledgeAssistant.chat(buildPrompt(principal, question, context, conversationHistory));
        tokenStream
                .onNext(onNext)
                .onComplete(response -> {
                    String answer = response.content() == null ? "" : response.content().text();
                    onComplete.accept(new AgentResult(answer, ToolTrackingHolder.get(), false));
                    AgentExecutionContextHolder.clear();
                    ToolTrackingHolder.clear();
                })
                .onError(error -> {
                    AgentExecutionContextHolder.clear();
                    ToolTrackingHolder.clear();
                    onError.accept(error);
                })
                .start();
    }

    private AgentResult answerWithDirectToolFallback(UserPrincipal principal, String question) {
        String normalizedQuestion = normalize(question);
        if (normalizedQuestion.isBlank()) {
            return null;
        }

        AgentExecutionContextHolder.set(principal.userId(), principal.role());
        try {
            if (isPureDocumentCountQuestion(normalizedQuestion)) {
                String answer = shouldUseTotalDocumentCount(principal, normalizedQuestion)
                        ? assistantTools.queryTotalDocumentCount()
                        : assistantTools.queryCurrentUserDocumentCount();
                return new AgentResult(answer, ToolTrackingHolder.get(), true);
            }
            if (isPureSystemStatusQuestion(normalizedQuestion)) {
                String answer = assistantTools.currentSystemStatus();
                return new AgentResult(answer, ToolTrackingHolder.get(), true);
            }
            return null;
        } finally {
            AgentExecutionContextHolder.clear();
            ToolTrackingHolder.clear();
        }
    }

    private boolean isPureDocumentCountQuestion(String normalizedQuestion) {
        boolean mentionsCount = normalizedQuestion.contains("文档数量")
                || normalizedQuestion.contains("文档总数")
                || normalizedQuestion.contains("多少文档")
                || normalizedQuestion.contains("几个文档")
                || normalizedQuestion.contains("文档个数")
                || normalizedQuestion.contains("数量");
        boolean mentionsSummary = normalizedQuestion.contains("总结")
                || normalizedQuestion.contains("概括")
                || normalizedQuestion.contains("分析")
                || normalizedQuestion.contains("内容")
                || normalizedQuestion.contains("面经")
                || normalizedQuestion.contains("制度");
        return mentionsCount && !mentionsSummary;
    }

    private boolean isPureSystemStatusQuestion(String normalizedQuestion) {
        return (normalizedQuestion.contains("系统状态")
                || normalizedQuestion.contains("当前状态")
                || normalizedQuestion.contains("运行状态")
                || normalizedQuestion.contains("服务状态"))
                && !normalizedQuestion.contains("总结");
    }

    private boolean shouldUseTotalDocumentCount(UserPrincipal principal, String normalizedQuestion) {
        if (!principal.role().equalsIgnoreCase("ADMIN")) {
            return false;
        }
        boolean asksPersonalCount = normalizedQuestion.contains("我的")
                || normalizedQuestion.contains("我自己")
                || normalizedQuestion.contains("当前用户")
                || normalizedQuestion.contains("本人");
        return !asksPersonalCount;
    }

    private String normalize(String text) {
        return text == null ? "" : text.replaceAll("\\s+", "");
    }

    private String buildPrompt(UserPrincipal principal, String question, String context, String conversationHistory) {
        return """
                当前用户信息:
                - userId: %s
                - role: %s

                最近对话历史:
                %s

                知识库上下文:
                %s

                用户问题:
                %s

                请结合最近对话和上下文回答；如果问题需要数据库数量、系统状态等外部信息，请调用工具。
                如果上下文不足，请明确说明。
                """.formatted(principal.userId(), principal.role(), conversationHistory, context, question);
    }

    public record AgentResult(String answer, String toolUsed, boolean directToolOnly) {
    }
}
