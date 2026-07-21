package com.sfc.ai.core;

import com.sfc.ai.constant.LlmMessageType;
import com.sfc.ai.core.channel.MessageChannel;
import com.sfc.ai.core.tool.ChannelMediatedToolCallback;
import com.sfc.ai.core.tool.RegisteredTool;
import com.sfc.ai.core.tool.ToolExecution;
import com.sfc.ai.model.chat.payload.ToolCallEndPayload;
import com.sfc.ai.model.chat.payload.ToolCallStatus;
import com.xiaotao.saltedfishcloud.model.po.UserPrincipal;
import com.xiaotao.saltedfishcloud.utils.SecureUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.execution.ToolExecutionException;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * 工具调用执行管理器。
 * <p>
 * 集中管理以下职责：
 * <ul>
 *   <li>已发送 {@link LlmMessageType#TOOL_CALL_START} 但尚未完成的工具调用注册表
 *       （{@link #announcedToolCalls}，id → toolName），涵盖<b>已通知但尚未执行</b>与
 *       <b>正在执行中</b>两种状态</li>
 *   <li>正在执行中的内建工具调用注册表（{@link #runningToolCalls}，id → {@link ToolExecution}），
 *       是 {@link #announcedToolCalls} 的子集，用于持有可中断的 Future 句柄</li>
 *   <li>待 ACK 的客户端中介工具调用注册表（{@link #pendingToolCalls}）</li>
 *   <li>内建工具虚拟线程执行器</li>
 *   <li>工具取消与确认</li>
 *   <li>单次工具调用执行（{@link #executeToolCall}）：安全上下文绑定、可中断执行、
 *       {@link LlmMessageType#TOOL_CALL_END} 生命周期通知</li>
 * </ul>
 * <p>
 * 通过 {@link #announcedToolCalls} 的原子 remove 仲裁谁负责发送
 * {@link LlmMessageType#TOOL_CALL_END}，避免重复发送：
 * <ul>
 *   <li>正常完成 / 工具异常 → {@link #executeToolCall} 负责发送（SUCCESS / ERROR）</li>
 *   <li>用户 STOP / 连接关闭中断 → {@link #cancelRunningTools} 负责发送（CANCELLED），
 *       覆盖<b>已通知但尚未执行</b>与<b>正在执行中</b>的全部工具调用</li>
 * </ul>
 */
@Slf4j
public class ToolExecutionManager {

    /**
     * 已发送 {@link LlmMessageType#TOOL_CALL_START} 但尚未完成的工具调用注册表。
     * <p>
     * key 为工具调用 ID，value 为工具名称。涵盖已通知但尚未执行与正在执行中两种状态，
     * 是 STOP / 连接关闭时发送 {@link LlmMessageType#TOOL_CALL_END}(CANCELLED) 的完整集合。
     */
    private final ConcurrentHashMap<String, String> announcedToolCalls = new ConcurrentHashMap<>();

    private final ConcurrentHashMap<String, ToolExecution> runningToolCalls = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CompletableFuture<String>> pendingToolCalls = new ConcurrentHashMap<>();
    private final ExecutorService toolExecutor = Executors.newVirtualThreadPerTaskExecutor();

    /**
     * 声明一个即将开始的工具调用。
     * <p>
     * 在发送 {@link LlmMessageType#TOOL_CALL_START} 之后、实际执行工具之前调用，
     * 将工具调用 ID 注册到 {@link #announcedToolCalls}，确保 STOP 中断时即使该工具
     * 尚未进入执行也能收到 {@link LlmMessageType#TOOL_CALL_END}(CANCELLED) 通知。
     *
     * @param toolCallId 工具调用 ID，与 {@link LlmMessageType#TOOL_CALL_START} 一致
     * @param toolName   工具名称
     */
    public void announceToolCall(String toolCallId, String toolName) {
        announcedToolCalls.put(toolCallId, toolName);
    }

    /**
     * 执行单次内建工具调用。
     * <p>
     * 将工具调用提交到独立的虚拟线程执行器执行，使其可被 {@link Thread#interrupt()} 硬中断，
     * 避免直接在 Reactor 共享线程上阻塞或被中断而污染线程池。
     * 执行完成后立即发送 {@link LlmMessageType#TOOL_CALL_END}(SUCCESS / ERROR)。
     * <p>
     * 通过 {@link #announcedToolCalls} 原子 remove 仲裁是否由本方法发送 END：
     * <ul>
     *   <li>成功 / 异常 → remove 返回非 null，本方法发送 END</li>
     *   <li>STOP 中断取消 → remove 返回 null（已被 {@link #cancelRunningTools} 移除并发送 CANCELLED），
     *       本方法不发送 END</li>
     * </ul>
     * <p>
     * 入口处检查 {@link #announcedToolCalls} 是否仍包含本工具调用 ID，若已被
     * {@link #cancelRunningTools} 移除则说明已发送 CANCELLED，本方法直接返回中断结果字符串，
     * 不再提交执行器执行，避免 STOP 后继续执行未开始的工具。
     *
     * @param callback    被调用的工具回调（未装饰的原始回调）
     * @param toolCallId  工具调用 ID，与 {@link LlmMessageType#TOOL_CALL_START} 一致
     * @param toolName    工具名称
     * @param toolInput   工具输入参数（JSON 字符串）
     * @param toolContext 工具上下文
     * @param user        当前用户，用于绑定 Spring Security 上下文；可为 null
     * @param channel     消息通道，用于发送 {@link LlmMessageType#TOOL_CALL_END}
     * @return 工具执行结果；若已被 STOP 取消则返回占位中断结果
     */
    public String executeToolCall(ToolCallback callback, String toolCallId, String toolName,
                                   String toolInput, ToolContext toolContext,
                                   UserPrincipal user, MessageChannel channel) {
        // 入口检查：若 STOP 已移除 announcedToolCalls 并发送 CANCELLED，则不再执行
        if (!announcedToolCalls.containsKey(toolCallId)) {
            log.debug("工具调用已被取消，跳过执行 id: {} name: {}", toolCallId, toolName);
            return "工具调用已被用户中断取消。";
        }

        Future<String> future = toolExecutor.submit(() -> {
            try {
                if (user != null) {
                    SecureUtils.bindUser(user);
                }
                return callback.call(toolInput, toolContext);
            } finally {
                if (user != null) {
                    SecureUtils.unbind();
                }
            }
        });
        runningToolCalls.put(toolCallId, new ToolExecution(future, toolCallId, toolName));

        try {
            String result = future.get();
            if (announcedToolCalls.remove(toolCallId) != null) {
                runningToolCalls.remove(toolCallId);
                notifyEnd(channel, toolCallId, toolName, result, ToolCallStatus.SUCCESS, null);
            }
            return result;
        } catch (CancellationException e) {
            announcedToolCalls.remove(toolCallId);
            runningToolCalls.remove(toolCallId);
            log.debug("工具调用被中断取消 id: {} name: {}", toolCallId, toolName);
            throw new ToolExecutionException(callback.getToolDefinition(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            announcedToolCalls.remove(toolCallId);
            runningToolCalls.remove(toolCallId);
            log.debug("工具调用等待被中断 id: {} name: {}", toolCallId, toolName);
            throw new ToolExecutionException(callback.getToolDefinition(), e);
        } catch (ExecutionException e) {
            if (announcedToolCalls.remove(toolCallId) != null) {
                runningToolCalls.remove(toolCallId);
                Throwable cause = e.getCause();
                notifyEnd(channel, toolCallId, toolName, null, ToolCallStatus.ERROR,
                        cause != null ? cause.getMessage() : e.getMessage());
            }
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            if (cause instanceof Error err) {
                throw err;
            }
            if (cause instanceof Exception ex) {
                throw new ToolExecutionException(callback.getToolDefinition(), ex);
            }
            throw new RuntimeException(e);
        }
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
     * 取消所有已发送 {@link LlmMessageType#TOOL_CALL_START} 但尚未完成的工具调用，
     * 对每个成功仲裁的调用发送 {@link LlmMessageType#TOOL_CALL_END}(CANCELLED)。
     * <p>
     * 遍历 {@link #announcedToolCalls}（而非仅 {@link #runningToolCalls}），覆盖：
     * <ul>
     *   <li>正在执行中的工具：取消其 Future 并发送 CANCELLED</li>
     *   <li>已通知但尚未进入执行的工具：直接发送 CANCELLED（无 Future 需取消）</li>
     * </ul>
     * 通过 {@link #announcedToolCalls} 原子 remove 仲裁：
     * <ul>
     *   <li>remove 返回非 null → 由本方法发送 CANCELLED</li>
     *   <li>remove 返回 null → {@link #executeToolCall} 已完成并发送 END，不重复发送</li>
     * </ul>
     *
     * @param reason  取消原因（如 "用户中断了工具调用"）
     * @param channel 消息通道，用于发送取消通知
     */
    public void cancelRunningTools(String reason, MessageChannel channel) {
        announcedToolCalls.forEach((toolCallId, toolName) -> {
            if (announcedToolCalls.remove(toolCallId) != null) {
                ToolExecution exec = runningToolCalls.remove(toolCallId);
                if (exec != null) {
                    exec.future().cancel(true);
                }
                sendCancelled(toolCallId, toolName, reason, channel);
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

    private static void notifyEnd(MessageChannel channel, String toolCallId, String toolName,
                                   String result, ToolCallStatus status, String errorMessage) {
        ToolCallEndPayload payload = new ToolCallEndPayload();
        payload.setId(toolCallId);
        payload.setName(toolName);
        payload.setResult(result);
        payload.setStatus(status);
        payload.setErrorMessage(errorMessage);
        channel.send(LlmMessageType.TOOL_CALL_END, payload);
        log.debug("工具调用完成 id: {} name: {} status: {}", toolCallId, toolName, status);
    }

    private static void sendCancelled(String toolCallId, String toolName, String reason, MessageChannel channel) {
        ToolCallEndPayload payload = new ToolCallEndPayload();
        payload.setId(toolCallId);
        payload.setName(toolName);
        payload.setStatus(ToolCallStatus.CANCELLED);
        payload.setErrorMessage(reason);
        channel.send(LlmMessageType.TOOL_CALL_END, payload);
    }
}
