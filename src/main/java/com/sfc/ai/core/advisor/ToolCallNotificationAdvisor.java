package com.sfc.ai.core.advisor;

import com.sfc.ai.constant.LlmMessageType;
import com.sfc.ai.core.channel.MessageChannel;
import com.sfc.ai.core.tool.ToolCallIdProvider;
import com.sfc.ai.model.chat.payload.ToolCallStartPayload;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工具调用通知 Advisor，在 LLM 流式响应中检测 tool_calls 并通过
 * {@link MessageChannel} 发送 {@link LlmMessageType#TOOL_CALL_START} 消息。
 * <p>
 * 在 Advisor 链中位于 {@link org.springframework.ai.chat.client.advisor.ToolCallingAdvisor} 与
 * {@link SfcChatMemoryAdvisor} 之间，确保在 ToolCallingAdvisor 实际执行工具之前先向客户端
 * 发送工具调用开始通知。
 * <p>
 * 使用 {@link ToolCallIdProvider} 生成工具调用 ID，通过 FIFO 队列传递给
 * {@code SfcAgentToolCallbackDecorator}，保证 {@code TOOL_CALL_START} 与
 * {@code TOOL_CALL_END} 使用相同的 ID。
 * <p>
 * 使用 {@link Set} 基于 LLM tool_call ID 去重，避免流式响应中重复发送通知。
 */
@Slf4j
public class ToolCallNotificationAdvisor extends SfcBaseAdvisor {

    private final MessageChannel channel;
    private final ToolCallIdProvider idProvider;
    private final Set<String> sentToolCallIds = ConcurrentHashMap.newKeySet();

    /**
     * 构造 Advisor。
     *
     * @param channel    消息通道，用于向客户端发送工具调用通知
     * @param idProvider 工具调用 ID 提供器，用于与 Decorator 共享调用 ID
     */
    public ToolCallNotificationAdvisor(MessageChannel channel, ToolCallIdProvider idProvider) {
        this.channel = channel;
        this.idProvider = idProvider;
    }

    @Override
    public @NonNull ChatClientRequest before(@NonNull ChatClientRequest request, @NonNull AdvisorChain chain) {
        return request;
    }

    @Override
    public @NonNull ChatClientResponse after(@NonNull ChatClientResponse response, @NonNull AdvisorChain chain) {
        return response;
    }

    @Override
    public @NonNull Flux<ChatClientResponse> adviseStream(@NonNull ChatClientRequest request,
                                                           @NonNull StreamAdvisorChain chain) {
        sentToolCallIds.clear();
        idProvider.clear();
        return Mono.just(request)
                .publishOn(getScheduler())
                .map(req -> this.before(req, chain))
                .flatMapMany(chain::nextStream)
                .doOnNext(this::detectAndNotifyToolCalls);
    }

    /**
     * 检测流式响应中的 tool_calls 并发送 TOOL_CALL_START 通知。
     *
     * @param response 当前流式响应
     */
    private void detectAndNotifyToolCalls(ChatClientResponse response) {
        ChatResponse chatResponse = response.chatResponse();
        if (chatResponse == null) {
            return;
        }
        for (Generation gen : chatResponse.getResults()) {
            AssistantMessage output = gen.getOutput();
            for (AssistantMessage.ToolCall toolCall : output.getToolCalls()) {
                if (sentToolCallIds.add(toolCall.id())) {
                    String toolCallId = idProvider.put();
                    ToolCallStartPayload payload = new ToolCallStartPayload();
                    payload.setId(toolCallId);
                    payload.setName(toolCall.name());
                    payload.setArguments(toolCall.arguments());
                    channel.send(LlmMessageType.TOOL_CALL_START, payload);
                    log.debug("工具调用开始 id: {} name: {}", toolCallId, toolCall.name());
                }
            }
        }
    }

    @Override
    public int getOrder() {
        return -1;
    }
}
