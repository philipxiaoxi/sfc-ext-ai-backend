package com.sfc.ai.core.tool;

import com.sfc.ai.constant.LlmMessageType;
import com.sfc.ai.core.channel.MessageChannel;
import com.sfc.ai.model.chat.payload.ToolCallEndPayload;
import com.sfc.ai.model.chat.payload.ToolCallStartPayload;
import com.xiaotao.saltedfishcloud.model.po.UserPrincipal;
import com.xiaotao.saltedfishcloud.utils.SecureUtils;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.ai.tool.execution.ToolExecutionException;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 适用于咸鱼云 AI Agent 的 {@link ToolCallback} 装饰器，为工具调用附加横切关注点：
 * <ul>
 *   <li>通过 {@link SecureUtils#bindUser} / {@link SecureUtils#unbind} 设置 Spring Security 线程上下文</li>
 *   <li>通过 {@link MessageChannel} 发送 {@link LlmMessageType#TOOL_CALL_START} / {@link LlmMessageType#TOOL_CALL_END} 生命周期通知</li>
 *   <li>将内建工具调用提交到独立的虚拟线程执行器执行，使其可被 {@link Thread#interrupt()} 硬中断，
 *       避免直接在 Reactor 共享线程上阻塞或被中断而污染线程池</li>
 * </ul>
 */
@Slf4j
public class SfcAgentToolCallbackDecorator implements ToolCallback {

    private final ToolCallback delegate;

    private final MessageChannel channel;

    private final UserPrincipal user;

    /** 内建工具执行器（per-session 虚拟线程池），用于使工具调用可被硬中断 */
    private final ExecutorService toolExecutor;

    /** 进行中的工具调用 Future 注册表，key 为工具调用 ID，供 STOP 硬中断 */
    private final Map<String, Future<?>> runningToolFutures;

    /** STOP 标志，true 时抑制 {@link LlmMessageType#TOOL_CALL_END} 与错误通知的发送 */
    private final AtomicBoolean stopped;

    /**
     * 构造装饰器。
     *
     * @param delegate           被装饰的实际工具回调
     * @param channel            消息通道，用于发送工具调用生命周期通知
     * @param user               当前用户，用于绑定 Spring Security 上下文
     * @param toolExecutor       内建工具执行的虚拟线程池
     * @param runningToolFutures 进行中工具调用的 Future 注册表
     * @param stopped            会话级 STOP 标志
     */
    public SfcAgentToolCallbackDecorator(ToolCallback delegate, MessageChannel channel, UserPrincipal user,
                                          ExecutorService toolExecutor,
                                          Map<String, Future<?>> runningToolFutures,
                                          AtomicBoolean stopped) {
        this.delegate = delegate;
        this.channel = channel;
        this.user = user;
        this.toolExecutor = toolExecutor;
        this.runningToolFutures = runningToolFutures;
        this.stopped = stopped;
    }

    @Override
    public @NonNull ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public @NonNull ToolMetadata getToolMetadata() {
        return delegate.getToolMetadata();
    }

    @Override
    public @NonNull String call(@NonNull String toolInput) {
        return delegate.call(toolInput);
    }

    @Override
    public @NonNull String call(@NonNull String toolInput, @NonNull ToolContext toolContext) {
        String toolCallId = UUID.randomUUID().toString();
        String toolName = getToolDefinition().name();

        notifyStart(toolCallId, toolName, toolInput);

        Future<String> future = toolExecutor.submit(() -> {
            try {
                if (user != null) {
                    SecureUtils.bindUser(user);
                }
                return delegate.call(toolInput, toolContext);
            } finally {
                if (user != null) {
                    SecureUtils.unbind();
                }
            }
        });
        runningToolFutures.put(toolCallId, future);

        try {
            String result = future.get();
            notifyEnd(toolCallId, toolName, result);
            return result;
        } catch (CancellationException e) {
            notifyEndIfNotStopped(toolCallId, toolName);
            log.debug("工具调用被中断取消 id: {} name: {}", toolCallId, toolName);
            throw new ToolExecutionException(getToolDefinition(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            notifyEndIfNotStopped(toolCallId, toolName);
            log.debug("工具调用等待被中断 id: {} name: {}", toolCallId, toolName);
            throw new ToolExecutionException(getToolDefinition(), e);
        } catch (ExecutionException e) {
            notifyEndIfNotStopped(toolCallId, toolName);
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            if (cause instanceof Error err) {
                throw err;
            }
            if (cause instanceof Exception ex) {
                throw new ToolExecutionException(getToolDefinition(), ex);
            }
            throw new RuntimeException(e);
        } finally {
            runningToolFutures.remove(toolCallId);
        }
    }

    private void notifyEndIfNotStopped(String toolCallId, String toolName) {
        if (stopped.get()) {
            return;
        }
        notifyEnd(toolCallId, toolName, null);
    }

    private void notifyStart(String toolCallId, String toolName, String arguments) {
        ToolCallStartPayload payload = new ToolCallStartPayload();
        payload.setId(toolCallId);
        payload.setName(toolName);
        payload.setArguments(arguments);
        channel.send(LlmMessageType.TOOL_CALL_START, payload);
        log.debug("工具调用开始 id: {} name: {}", toolCallId, toolName);
    }

    private void notifyEnd(String toolCallId, String toolName, String result) {
        ToolCallEndPayload payload = new ToolCallEndPayload();
        payload.setId(toolCallId);
        payload.setName(toolName);
        payload.setResult(result);
        channel.send(LlmMessageType.TOOL_CALL_END, payload);
        log.debug("工具调用完成 id: {} name: {}", toolCallId, toolName);
    }
}
