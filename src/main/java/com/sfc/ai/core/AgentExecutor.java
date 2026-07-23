package com.sfc.ai.core;

import com.sfc.ai.constant.LlmMessageType;
import com.sfc.ai.core.channel.MessageChannel;
import com.sfc.ai.core.memory.ChatMemoryRepairer;
import com.sfc.ai.core.tool.RegisteredTool;
import com.sfc.ai.model.chat.payload.ChatPayload;
import com.sfc.ai.model.chat.payload.DonePayload;
import com.sfc.ai.model.chat.payload.RegisterToolPayload;
import com.sfc.ai.model.chat.payload.SessionAckPayload;
import com.sfc.ai.model.chat.payload.StartSessionPayload;
import com.sfc.ai.model.chat.payload.ToolCallAckPayload;
import com.sfc.ai.model.chat.message.UserRequest;
import com.sfc.ai.service.AiConversationService;
import com.xiaotao.saltedfishcloud.exception.JsonException;
import com.xiaotao.saltedfishcloud.utils.MapperHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * AI Agent 执行器默认实现。
 * <p>
 * 仅承担消息路由 / 会话生命周期 / 工具调用协作 / 资源释放。LLM 解析、ChatClient
 * 构造、流式订阅主体逻辑委托给 {@link ChatRunner}；记忆补偿委托给
 * {@link ChatMemoryRepairer}；首聊标题生成按 {@link AgentExecutorConfig#isAutoGenerateTitle()}
 * 透传至 {@link ConversationTitleGenerator}。
 * <p>
 * 持有 {@link MessageChannel} 引用用于消息收发，内部管理 {@link SessionContext}
 * 会话状态与 {@link ToolExecutionManager} 工具执行。
 * <p>
 * 创建后需调用 {@link #start()} 激活执行器，使用完毕后调用 {@link #close()} 释放资源。
 */
@Slf4j
public class AgentExecutor {

    /** 下行消息通道 */
    private final MessageChannel channel;

    /** CHAT 请求执行器（无状态 Spring 服务） */
    private final ChatRunner chatRunner;

    /** 记忆补偿修复器（无状态 Spring 服务） */
    private final ChatMemoryRepairer compensator;

    /** 会话持久化服务，用于 START_SESSION 时判断会话是否已存在 */
    private final AiConversationService aiConversationService;

    /** Agent 执行器配置 */
    private final AgentExecutorConfig config;

    /** 工具执行管理器，per-conn 状态 */
    private final ToolExecutionManager toolExecutionManager = new ToolExecutionManager();

    /** 会话上下文，per-conn 状态 */
    private final SessionContext sessionContext = new SessionContext();

    /** 当前连接已注册的客户端中介工具集合（key 工具名 → value 工具回调），per-conn 状态 */
    private final Map<String, ToolCallback> registeredTools = new ConcurrentHashMap<>();

    /** 关闭标志位，CAS 保证幂等关闭 */
    private final AtomicBoolean stopped = new AtomicBoolean(false);

    /**
     * 构造 Agent 执行器。
     *
     * @param channel               下行消息通道
     * @param chatRunner            CHAT 请求执行器
     * @param compensator            记忆补偿修复器
     * @param aiConversationService 会话持久化服务
     * @param config                Agent 执行器配置
     */
    public AgentExecutor(MessageChannel channel,
                          ChatRunner chatRunner,
                          ChatMemoryRepairer compensator,
                          AiConversationService aiConversationService,
                          AgentExecutorConfig config) {
        this.channel = channel;
        this.chatRunner = chatRunner;
        this.compensator = compensator;
        this.aiConversationService = aiConversationService;
        this.config = config;
    }

    /**
     * 启动 Agent 执行器，注册消息通道处理器开始处理请求。
     */
    public void start() {
        stopped.set(false);
        channel.onMessage(this::dispatch);
        channel.onClose(this::onChannelClosed);
        log.debug("Agent 执行器已启动");
    }

    /**
     * 关闭 Agent 执行器，取消所有进行中的操作并清理资源。
     * <p>
     * 幂等方法，多次调用仅首次生效。调用后执行器不再可用。
     */
    public void close() {
        if (!stopped.compareAndSet(false, true)) {
            return;
        }
        log.debug("关闭 Agent 执行器");
        toolExecutionManager.cancelAll("执行器已关闭，中断了工具调用", channel);
        toolExecutionManager.shutdown();
        sessionContext.dispose();
        compensateDanglingToolCalls();
        sessionContext.clearSession();
    }

    /**
     * 通道关闭回调，委托给 {@link #close()}。
     */
    private void onChannelClosed() {
        log.debug("消息通道已关闭，取消所有进行中的 AI 操作");
        close();
    }

    /**
     * 根据 {@link UserRequest#getType()} 将请求分发到对应的处理器，捕获所有未处理异常并下发错误信息。
     *
     * @param request 来自客户端的用户请求
     */
    private void dispatch(UserRequest request) {
        try {
            if (request.getType() == null) {
                channel.sendError("消息类型不能为空");
                return;
            }
            switch (request.getType()) {
                case START_SESSION -> handleStartSession(request);
                case CHAT -> handleChat(request);
                case TOOL_ACK -> handleToolAck(request);
                case STOP -> handleStop();
                case REGISTER_TOOL -> handleRegisterTool(request);
                default -> channel.sendError("未知消息类型: " + request.getType());
            }
        } catch (Throwable e) {
            channel.sendError("处理消息时发生错误: " + e.getMessage());
            log.error("处理 AI 消息时发生错误", e);
        }
    }

    /**
     * 处理 START_SESSION 请求：解析会话 ID（缺失则生成 UUID），通过
     * {@link AiConversationService#existsByConversationId(String)} 判断是否新会话，
     * 在 {@link SessionContext} 中开启会话并下发 {@link LlmMessageType#SESSION_ACK}。
     *
     * @param request 包含 {@link StartSessionPayload} 的用户请求
     */
    private void handleStartSession(UserRequest request) {
        StartSessionPayload startPayload = MapperHolder.mapper.convertValue(
                request.getData(), StartSessionPayload.class);
        String sessionId = startPayload.getSessionId();
        if (sessionId == null || sessionId.isBlank()) {
            sessionId = UUID.randomUUID().toString();
        }

        boolean isNew = !aiConversationService.existsByConversationId(sessionId);
        sessionContext.startSession(sessionId, channel.getUser(), isNew);
        channel.send(LlmMessageType.SESSION_ACK, new SessionAckPayload(sessionId));
    }

    /**
     * 处理 CHAT 请求：校验会话与 payload，委托 {@link ChatRunner#run} 执行一次 LLM 调用全流程。
     * <p>
     * {@link ChatRunner} 在内部抛出的 {@link JsonException}（来自 {@link LlmResolver} 解析失败）
     * 由本方法捕获并经 {@link MessageChannel#sendError(String)} 下发，不再上抛至
     * {@link #dispatch} 的 {@code catch (Throwable)}。
     *
     * @param request 包含 {@link ChatPayload} 的用户请求
     */
    private void handleChat(UserRequest request) {
        if (!sessionContext.hasSession()) {
            channel.sendError("请先发送 START_SESSION 消息开启会话");
            return;
        }
        ChatPayload chatData = MapperHolder.mapper.convertValue(request.getData(), ChatPayload.class);
        if (chatData.getModelId() == null || chatData.getContent() == null) {
            channel.sendError("CHAT 消息缺少 modelId 或 content");
            return;
        }
        try {
            chatRunner.run(chatData, channel, sessionContext,
                    toolExecutionManager, registeredTools, config);
        } catch (JsonException e) {
            channel.sendError(e.getMessage());
        }
    }

    /**
     * 处理 REGISTER_TOOL 请求：将客户端提供的工具元信息注册为 channel 中介工具回调，
     * 存入 {@link #registeredTools} 并下发 {@link LlmMessageType#REGISTER_TOOL_ACK}。
     *
     * @param request 包含 {@link RegisterToolPayload} 的用户请求
     */
    private void handleRegisterTool(UserRequest request) {
        RegisterToolPayload payload = MapperHolder.mapper.convertValue(
                request.getData(), RegisterToolPayload.class);
        registeredTools.put(payload.getName(),
                toolExecutionManager.createChannelMediatedTool(
                        new RegisteredTool(payload.getName(), payload.getDescription(), payload.getParameters()),
                        channel));
        log.debug("通过 MessageChannel 注册工具: {}", payload.getName());
        channel.send(LlmMessageType.REGISTER_TOOL_ACK, payload.getName());
    }

    /**
     * 处理 TOOL_ACK 请求：将客户端返回的工具执行结果通过
     * {@link ToolExecutionManager#acknowledge(String, String)} 完成待 ACK 的客户端中介工具调用。
     *
     * @param request 包含 {@link ToolCallAckPayload} 的用户请求
     */
    private void handleToolAck(UserRequest request) {
        ToolCallAckPayload ack = MapperHolder.mapper.convertValue(
                request.getData(), ToolCallAckPayload.class);
        if (!toolExecutionManager.acknowledge(ack.getId(), ack.getResult())) {
            log.warn("收到未知工具调用 ID 的 TOOL_ACK: {}", ack.getId());
        }
    }

    /**
     * 处理用户主动中断请求。
     * <p>
     * 依次：
     * <ol>
     *   <li>通过 {@link SessionContext#stop()} 标记停止状态</li>
     *   <li>通过 {@link ToolExecutionManager#cancelAll(String, MessageChannel)} 取消所有进行中的工具调用</li>
     *   <li>通过 {@link SessionContext#dispose()} 释放响应流与异步任务</li>
     *   <li>通过 {@link #compensateDanglingToolCalls()} 修复对话记忆中悬空的工具调用记录</li>
     *   <li>通过 {@link SessionContext#trySendDone()} 去重发送一次 {@link LlmMessageType#DONE} "已停止"</li>
     * </ol>
     */
    private void handleStop() {
        sessionContext.stop();
        toolExecutionManager.cancelAll("用户中断了工具调用", channel);
        sessionContext.dispose();
        compensateDanglingToolCalls();

        if (sessionContext.trySendDone()) {
            DonePayload donePayload = new DonePayload();
            donePayload.setReason("已停止");
            channel.send(LlmMessageType.DONE, donePayload);
        }
    }

    /**
     * 补偿修复对话记忆中悬空的工具调用记录。委托给 {@link ChatMemoryRepairer#compensate(String)}。
     * 会话不存在时无操作。
     */
    private void compensateDanglingToolCalls() {
        if (!sessionContext.hasSession()) {
            return;
        }
        compensator.compensate(sessionContext.getConversationId());
    }
}