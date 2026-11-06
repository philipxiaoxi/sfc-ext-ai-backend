package com.sfc.ai.core.adapter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.sfc.ai.core.message.ReasoningContentSupport;
import com.sfc.ai.model.po.LlmModel;
import com.sfc.ai.model.po.LlmProvider;
import com.sfc.common.utils.SfcMapperHolder;
import com.sfc.common.utils.SfcStringUtils;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.deepseek.DeepSeekAssistantMessage;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.deepseek.api.DeepSeekApi;
import org.springframework.http.HttpHeaders;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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

                .headers(
                        Optional.ofNullable(provider.getCustomHeader())
                                .map(h -> {
                                    HttpHeaders httpHeaders = new HttpHeaders();
                                    try {
                                        SfcMapperHolder.parseJsonToMap(h, String.class, String.class).forEach(httpHeaders::add);
                                    } catch (JsonProcessingException e) {
                                        throw new RuntimeException(e);
                                    }
                                    return httpHeaders;
                                })
                                .orElseGet(HttpHeaders::new)
                )
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

    @Override
    public List<Message> preprocessMessages(List<Message> messages) {
        return messages.stream()
                .map(msg -> {
                    if (msg instanceof AssistantMessage am
                            && msg instanceof ReasoningContentSupport rcs
                            && !(msg instanceof DeepSeekAssistantMessage)) {
                        String reasoning = rcs.getReasoningContent();
                        return new DeepSeekAssistantMessage.Builder()
                                .content(am.getText())
                                .reasoningContent(SfcStringUtils.hasText(reasoning) ? reasoning : null)
                                .properties(am.getMetadata())
                                .toolCalls(am.getToolCalls())
                                .media(am.getMedia())
                                .build();
                    }
                    return msg;
                })
                .toList();
    }
}
