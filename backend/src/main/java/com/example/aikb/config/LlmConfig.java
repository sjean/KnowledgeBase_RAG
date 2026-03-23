package com.example.aikb.config;

import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class LlmConfig {

    @Bean
    public OpenAiChatModel openAiChatModel(AppProperties properties) {
        AppProperties.Llm llm = properties.getLlm();
        return OpenAiChatModel.builder()
                .apiKey(llm.getApiKey())
                .baseUrl(llm.getBaseUrl())
                .modelName(llm.getModelName())
                .logRequests(llm.isLogRequests())
                .logResponses(llm.isLogResponses())
                .build();
    }

    @Bean
    public OpenAiEmbeddingModel openAiEmbeddingModel(AppProperties properties) {
        AppProperties.Llm llm = properties.getLlm();
        return OpenAiEmbeddingModel.builder()
                .apiKey(llm.getApiKey())
                .baseUrl(llm.getBaseUrl())
                .modelName(llm.getEmbeddingModel())
                .logRequests(false)
                .logResponses(false)
                .build();
    }
}
