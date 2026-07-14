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

import java.util.UUID;

/**
 * 适用于咸鱼云 AI Agent 的 {@link ToolCallback} 装饰器，为工具调用附加横切关注点：
 * <ul>
 *   <li>通过 {@link SecureUtils#bindUser} / {@link SecureUtils#unbind} 设置 Spring Security 线程上下文</li>
 *   <li>通过 {@link MessageChannel} 发送 {@link LlmMessageType#TOOL_CALL_START} / {@link LlmMessageType#TOOL_CALL_END} 生命周期通知</li>
 * </ul>
 */
@Slf4j
public class SfcAgentToolCallbackDecorator implements ToolCallback {

    private final ToolCallback delegate;

    private final MessageChannel channel;

    private final UserPrincipal user;

    public SfcAgentToolCallbackDecorator(ToolCallback delegate, MessageChannel channel, UserPrincipal user) {
        this.delegate = delegate;
        this.channel = channel;
        this.user = user;
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

        try {
            if (user != null) {
                SecureUtils.bindUser(user);
            }
            String result = delegate.call(toolInput, toolContext);
            notifyEnd(toolCallId, toolName, result);
            return result;
        } catch (Exception e) {
            notifyEnd(toolCallId, toolName, null);
            throw e;
        } finally {
            if (user != null) {
                SecureUtils.unbind();
            }
        }
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
