package com.sfc.ai.advisor;

import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.messages.Message;

/**
 * todo 待实现
 * 用于获取 LLM 的 Tool Call 请求，并在请求执行完成后获取结果，将 LLM 的调用请求 - 调用完成 信息发送给前端
 */
@Slf4j
public class SimpleToolCallAdvise implements BaseAdvisor {
    private final static String LOG_PREFIX = "[LLM Tool Call]";

    @Override
    public @NonNull ChatClientRequest before(@NonNull ChatClientRequest chatClientRequest, @NonNull AdvisorChain advisorChain) {
        Message lastUserOrToolResponseMessage = chatClientRequest.prompt().getLastUserOrToolResponseMessage();
        log.debug("{} 上一次用户或工具响应消息: {}", LOG_PREFIX, lastUserOrToolResponseMessage);
        return chatClientRequest;
    }

    @Override
    public @NonNull ChatClientResponse after(@NonNull ChatClientResponse chatClientResponse, @NonNull AdvisorChain advisorChain) {
        if(chatClientResponse.chatResponse() != null && chatClientResponse.chatResponse().hasToolCalls()) {
            chatClientResponse.chatResponse().getResults()
                    .stream()
                    .filter(g -> g.getOutput().hasToolCalls())
                    .flatMap(g -> g.getOutput().getToolCalls().stream())
                    .forEach(toolCall -> log.debug("{} id: {} 调用工具: {} 参数: {}", LOG_PREFIX, toolCall.id(), toolCall.name(), toolCall.arguments()));
        }
        return chatClientResponse;
    }

    @Override
    public int getOrder() {
        return Integer.MAX_VALUE;
    }
}
