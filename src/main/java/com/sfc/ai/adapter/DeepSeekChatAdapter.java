package com.sfc.ai.adapter;

import com.sfc.ai.model.po.LlmModel;
import com.sfc.ai.model.po.LlmProvider;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.deepseek.DeepSeekAssistantMessage;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.deepseek.api.DeepSeekApi;

/**
 * DeepSeek 适配器，使用 Spring AI 的 {@link DeepSeekChatModel} 创建聊天模型实例。
 */
public class DeepSeekChatAdapter implements LlmChatAdapter {

    @Override
    public String getId() {
        return "deepseek";
    }

    @Override
    public String getName() {
        return "DeepSeek";
    }

    @Override
    public ChatModel createChatModel(LlmProvider provider, LlmModel model) {
        DeepSeekApi deepSeekApi = DeepSeekApi.builder()
                .apiKey(provider.getApiKey())
                .baseUrl(provider.getBaseUrl())
                .build();

        DeepSeekChatOptions options = DeepSeekChatOptions.builder()
                .model(model.getModelId())
                .build();

        return DeepSeekChatModel.builder()
                .deepSeekApi(deepSeekApi)
                .options(options)
                .build();
    }

    @Nullable
    @Override
    public String extractReasoningContent(AssistantMessage message) {
        return message instanceof DeepSeekAssistantMessage ds
                ? ds.getReasoningContent() : null;
    }
}
