package com.sfc.ai.core.tool;

import com.sfc.ai.constant.LlmMessageType;
import com.sfc.ai.core.channel.MessageChannel;
import com.sfc.ai.model.chat.payload.ToolCallStartPayload;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.execution.ToolExecutionException;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 通过 MessageChannel 向客户端委托执行的动态工具回调。
 * <p>
 * {@link #call(String)} 执行流程：
 * <ol>
 *   <li>生成工具调用 ID</li>
 *   <li>在 {@code pendingToolCalls} 中注册 {@link CompletableFuture}</li>
 *   <li>发送 {@link LlmMessageType#TOOL_CALL_REQ} 消息给客户端</li>
 *   <li>阻塞等待客户端返回 {@code TOOL_ACK}（最多 60 秒）</li>
 *   <li>返回执行结果</li>
 * </ol>
 */
@RequiredArgsConstructor
public class ChannelMediatedToolCallback implements ToolCallback {

    private final RegisteredTool tool;
    private final MessageChannel channel;
    private final ConcurrentHashMap<String, CompletableFuture<String>> pendingToolCalls;

    private ToolDefinition toolDefinition;

    @Override
    public @NonNull ToolDefinition getToolDefinition() {
        if (toolDefinition == null) {
            toolDefinition = ToolDefinition.builder()
                    .name(tool.getName())
                    .description(tool.getDescription())
                    .inputSchema(tool.getInputSchema())
                    .build();
        }
        return toolDefinition;
    }

    @Override
    public @NonNull String call(@NonNull String toolInput) {
        String toolCallId = UUID.randomUUID().toString();
        CompletableFuture<String> future = new CompletableFuture<>();
        pendingToolCalls.put(toolCallId, future);

        try {
            ToolCallStartPayload payload = new ToolCallStartPayload();
            payload.setId(toolCallId);
            payload.setName(tool.getName());
            payload.setArguments(toolInput);
            channel.send(LlmMessageType.TOOL_CALL_REQ, payload);
            return future.get(60, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new ToolExecutionException(toolDefinition, e);
        } finally {
            pendingToolCalls.remove(toolCallId);
        }
    }
}
