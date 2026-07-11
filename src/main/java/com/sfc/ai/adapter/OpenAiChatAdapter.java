package com.sfc.ai.adapter;

import com.sfc.ai.model.po.LlmModel;
import com.sfc.ai.model.po.LlmProvider;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;

/**
 * OpenAI 适配器，使用 Spring AI 的 {@link OpenAiChatModel} 创建聊天模型实例。
 */
public class OpenAiChatAdapter implements LlmChatAdapter {

    @Override
    public String getId() {
        return "openai";
    }

    @Override
    public String getName() {
        return "OpenAI";
    }

    @Override
    public ChatModel createChatModel(LlmProvider provider, LlmModel model) {
        return OpenAiChatModel.builder()
                .options(OpenAiChatOptions.builder()
                        .model(model.getModelId())
                        .apiKey(provider.getApiKey())
                        .baseUrl(provider.getBaseUrl())
                        .reasoningEffort(model.getReasoningEffect())
                        .build())
                .build();
    }
}
