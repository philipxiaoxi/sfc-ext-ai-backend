package com.sfc.ai.core.adapter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.sfc.ai.model.po.LlmModel;
import com.sfc.ai.model.po.LlmProvider;
import com.sfc.common.utils.SfcMapperHolder;
import com.sfc.common.utils.SfcStringUtils;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;

import java.util.Collections;
import java.util.Map;
import java.util.Optional;

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
                        .customHeaders(
                                Optional.ofNullable(provider.getCustomHeader())
                                        .filter(SfcStringUtils::hasText)
                                        .map(h -> {
                                            try {
                                                return SfcMapperHolder.mapper.readValue(h, new TypeReference<Map<String, String>>(){});
                                            } catch (JsonProcessingException e) {
                                                throw new RuntimeException(e);
                                            }
                                        })
                                        .orElse(Collections.emptyMap())
                        )
                        .apiKey(provider.getApiKey())
                        .baseUrl(provider.getBaseUrl())
                        .reasoningEffort(model.getReasoningEffect())
                        .build())
                .build();
    }
}
