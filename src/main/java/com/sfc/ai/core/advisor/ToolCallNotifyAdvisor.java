package com.sfc.ai.core.advisor;

import com.sfc.ai.model.chat.payload.ToolCallEndPayload;
import com.sfc.ai.model.chat.payload.ToolCallStartPayload;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * 用于追踪 LLM 的 Tool Call 生命周期，在工具调用开始和结束时分别通过回调通知外部。
 * <p>
 * 内部维护 {@link #pendingToolCallIds} 用于关联 TOOL_CALL_START 与 TOOL_CALL_END：
 * <ul>
 *   <li>在 {@link #after} 检测 LLM 响应中的 tool call，记录 id 并回调 {@code onToolCallStart}</li>
 *   <li>在 {@link #before} 检测请求消息中的 {@link ToolResponseMessage}，匹配 id 后回调 {@code onToolCallEnd} 并移除追踪</li>
 * </ul>
 */
@Slf4j
public class ToolCallNotifyAdvisor extends SfcBaseAdvisor {

    private final static String LOG_PREFIX = "[LLM Tool Call]";

    private final Set<String> pendingToolCallIds = ConcurrentHashMap.newKeySet();

    private final Consumer<ToolCallStartPayload> onToolCallStart;

    private final Consumer<ToolCallEndPayload> onToolCallEnd;

    /**
     * @param onToolCallStart 工具调用开始回调，参数为 {@link ToolCallStartPayload}
     * @param onToolCallEnd   工具调用结束回调，参数为 {@link ToolCallEndPayload}
     */
    public ToolCallNotifyAdvisor(Consumer<ToolCallStartPayload> onToolCallStart,
                                 Consumer<ToolCallEndPayload> onToolCallEnd) {
        this.onToolCallStart = onToolCallStart;
        this.onToolCallEnd = onToolCallEnd;
    }

    @Override
    public @NonNull ChatClientRequest before(@NonNull ChatClientRequest chatClientRequest,
                                              @NonNull AdvisorChain advisorChain) {
        Message lastUserOrToolResponseMessage = chatClientRequest.prompt().getLastUserOrToolResponseMessage();
        if (lastUserOrToolResponseMessage instanceof ToolResponseMessage trm) {
            for (var tr : trm.getResponses()) {
                if (pendingToolCallIds.remove(tr.id())) {
                    log.debug("{} 工具调用完成 id: {} name: {}", LOG_PREFIX, tr.id(), tr.name());
                    ToolCallEndPayload payload = new ToolCallEndPayload();
                    payload.setId(tr.id());
                    payload.setName(tr.name());
                    payload.setResult(tr.responseData());
                    onToolCallEnd.accept(payload);
                }
            }
        }
        return chatClientRequest;
    }

    @Override
    public @NonNull ChatClientResponse after(@NonNull ChatClientResponse chatClientResponse,
                                              @NonNull AdvisorChain advisorChain) {
        if (chatClientResponse.chatResponse() != null && chatClientResponse.chatResponse().hasToolCalls()) {
            chatClientResponse.chatResponse().getResults()
                    .stream()
                    .filter(g -> g.getOutput().hasToolCalls())
                    .flatMap(g -> g.getOutput().getToolCalls().stream())
                    .forEach(toolCall -> {
                        pendingToolCallIds.add(toolCall.id());
                        log.debug("{} 工具调用开始 id: {} name: {} 参数: {}",
                                LOG_PREFIX, toolCall.id(), toolCall.name(), toolCall.arguments());
                        ToolCallStartPayload payload = new ToolCallStartPayload();
                        payload.setId(toolCall.id());
                        payload.setName(toolCall.name());
                        payload.setArguments(toolCall.arguments());
                        onToolCallStart.accept(payload);
                    });
        }
        return chatClientResponse;
    }

    @Override
    public int getOrder() {
        return Integer.MAX_VALUE;
    }
}
