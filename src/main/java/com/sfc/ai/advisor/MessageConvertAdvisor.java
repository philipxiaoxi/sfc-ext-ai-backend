package com.sfc.ai.advisor;

import com.sfc.ai.adapter.LlmChatAdapter;
import org.jspecify.annotations.NonNull;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;

/**
 * 调用 {@link LlmChatAdapter#preprocessMessages} 对消息列表进行厂商特定预处理的 Advisor。
 * <p>
 * 必须在 {@link SfcChatMemoryAdvisor} 之后执行，确保此时历史记忆消息已注入到 prompt 中。
 */
public class MessageConvertAdvisor implements BaseAdvisor {

    private final LlmChatAdapter adapter;

    public MessageConvertAdvisor(LlmChatAdapter adapter) {
        this.adapter = adapter;
    }

    @Override
    public @NonNull ChatClientRequest before(@NonNull ChatClientRequest request, @NonNull AdvisorChain chain) {
        var messages = request.prompt().getInstructions();
        var converted = adapter.preprocessMessages(messages);
        return request.mutate()
                .prompt(request.prompt().mutate().messages(converted).build())
                .build();
    }

    @Override
    public @NonNull ChatClientResponse after(@NonNull ChatClientResponse response, @NonNull AdvisorChain chain) {
        return response;
    }

    @Override
    public int getOrder() {
        return 1;
    }
}
