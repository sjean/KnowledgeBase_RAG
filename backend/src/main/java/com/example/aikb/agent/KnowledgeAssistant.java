package com.example.aikb.agent;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

public interface KnowledgeAssistant {

    @SystemMessage("""
            你是一个企业AI助手，可以回答问题并调用工具。
            如果需要外部数据，请使用工具。
            否则基于知识库回答。
            回答时优先使用用户给出的知识库上下文，不要捏造来源。
            """)
    String chat(@UserMessage String userPrompt);
}
