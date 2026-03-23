package com.example.aikb.agent;

import com.example.aikb.security.UserPrincipal;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.AiServices;
import org.springframework.stereotype.Service;

@Service
public class AgentService {

    private final KnowledgeAssistant knowledgeAssistant;

    public AgentService(OpenAiChatModel chatModel, AssistantTools assistantTools) {
        this.knowledgeAssistant = AiServices.builder(KnowledgeAssistant.class)
                .chatLanguageModel(chatModel)
                .tools(assistantTools)
                .build();
    }

    public AgentResult ask(UserPrincipal principal, String question, String context) {
        ToolTrackingHolder.clear();
        String prompt = """
                当前用户信息:
                - userId: %s
                - role: %s

                知识库上下文:
                %s

                用户问题:
                %s

                请基于上下文回答；如果问题需要数据库数量、系统状态等外部信息，请调用工具。
                如果上下文不足，请明确说明。
                """.formatted(principal.userId(), principal.role(), context, question);

        String answer = knowledgeAssistant.chat(prompt);
        return new AgentResult(answer, ToolTrackingHolder.get());
    }

    public record AgentResult(String answer, String toolUsed) {
    }
}
