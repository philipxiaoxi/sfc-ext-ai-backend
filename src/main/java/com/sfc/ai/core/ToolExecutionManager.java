package com.sfc.ai.core;

import com.sfc.ai.constant.LlmMessageType;
import com.sfc.ai.core.channel.MessageChannel;
import com.sfc.ai.core.tool.ChannelMediatedToolCallback;
import com.sfc.ai.core.tool.RegisteredTool;
import com.sfc.ai.core.tool.SfcAgentToolCallbackDecorator;
import com.sfc.ai.core.tool.ToolCallIdProvider;
import com.sfc.ai.core.tool.ToolExecution;
import com.sfc.ai.model.chat.payload.ToolCallEndPayload;
import com.sfc.ai.model.chat.payload.ToolCallStatus;
import com.xiaotao.saltedfishcloud.model.po.UserPrincipal;
import org.springframework.ai.tool.ToolCallback;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 工具调用执行管理器。
 * <p>
 * 集中管理以下职责：
 * <ul>
 *   <li>进行中的内建工具调用注册表（{@link #runningToolCalls}）</li>
 *   <li>待 ACK 的客户端中介工具调用注册表（{@link #pendingToolCalls}）</li>
 *   <li>内建工具虚拟线程执行器</li>
 *   <li>工具取消与确认</li>
 * </ul>
 * 通过原子 remove 仲裁与 {@link SfcAgentToolCallbackDecorator} 协作，
 * 避免 {@link LlmMessageType#TOOL_CALL_END} 重复发送。
 */
public class ToolExecutionManager {

    private final ConcurrentHashMap<String, ToolExecution> runningToolCalls = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CompletableFuture<String>> pendingToolCalls = new ConcurrentHashMap<>();
    private final ExecutorService toolExecutor = Executors.newVirtualThreadPerTaskExecutor();

    /**
     * 装饰一个工具回调，附加生命周期通知与可中断执行能力。
     *
     * @param delegate    被装饰的实际工具回调
     * @param channel     消息通道，用于发送 TOOL_CALL_END
     * @param user        当前用户，用于绑定安全上下文
     * @param idProvider  工具调用 ID 提供器
     * @return 装饰后的工具回调
     */
    public ToolCallback decorate(ToolCallback delegate, MessageChannel channel,
                                  UserPrincipal user, ToolCallIdProvider idProvider) {
        return new SfcAgentToolCallbackDecorator(
                delegate, channel, user, toolExecutor, runningToolCalls, idProvider);
    }

    /**
     * 创建一个客户端中介工具回调，将工具执行委托给前端客户端。
     *
     * @param tool    注册的工具元信息
     * @param channel 消息通道，用于发送 TOOL_CALL_REQ
     * @return 客户端中介工具回调
     */
    public ToolCallback createChannelMediatedTool(RegisteredTool tool, MessageChannel channel) {
        return new ChannelMediatedToolCallback(tool, channel, pendingToolCalls);
    }

    /**
     * 确认（ACK）一个客户端中介工具调用的结果。
     *
     * @param callId 工具调用 ID
     * @param result 工具执行结果
     * @return true 如果找到并完成对应的 Future，false 表示未知 ID
     */
    public boolean acknowledge(String callId, String result) {
        CompletableFuture<String> future = pendingToolCalls.remove(callId);
        if (future != null) {
            future.complete(result);
            return true;
        }
        return false;
    }

    /**
     * 取消所有进行中的内建工具调用。
     * 对每个成功取消的调用发送 {@link LlmMessageType#TOOL_CALL_END}(CANCELLED)。
     *
     * @param reason  取消原因（如 "用户中断了工具调用"）
     * @param channel 消息通道，用于发送取消通知
     */
    public void cancelRunningTools(String reason, MessageChannel channel) {
        runningToolCalls.forEach((toolCallId, exec) -> {
            if (exec.future().cancel(true)) {
                runningToolCalls.remove(toolCallId);
                sendCancelled(exec, reason, channel);
            }
        });
        runningToolCalls.clear();
    }

    /**
     * 取消所有待 ACK 的客户端中介工具调用。
     */
    public void cancelPendingTools() {
        pendingToolCalls.values().forEach(f -> f.cancel(true));
        pendingToolCalls.clear();
    }

    /**
     * 取消所有工具调用（内建 + 客户端中介）。
     *
     * @param reason  取消原因
     * @param channel 消息通道
     */
    public void cancelAll(String reason, MessageChannel channel) {
        cancelRunningTools(reason, channel);
        cancelPendingTools();
    }

    /**
     * 关闭虚拟线程执行器。
     */
    public void shutdown() {
        toolExecutor.shutdownNow();
    }

    private static void sendCancelled(ToolExecution exec, String reason, MessageChannel channel) {
        ToolCallEndPayload payload = new ToolCallEndPayload();
        payload.setId(exec.toolCallId());
        payload.setName(exec.toolName());
        payload.setStatus(ToolCallStatus.CANCELLED);
        payload.setErrorMessage(reason);
        channel.send(LlmMessageType.TOOL_CALL_END, payload);
    }
}
