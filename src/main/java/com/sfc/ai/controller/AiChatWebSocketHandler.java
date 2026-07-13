package com.sfc.ai.controller;

import com.sfc.ai.core.AgentExecutorFactory;
import com.sfc.ai.core.channel.WebSocketMessageChannel;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * AI 聊天 WebSocket 处理器（传输层适配器）。
 * <p>
 * 仅参与 Agent 会话的初始化创建与销毁：
 * <ul>
 *   <li>连接建立 → 创建 {@link WebSocketMessageChannel} + AgentExecutor</li>
 *   <li>消息到达 → 转发给 {@link WebSocketMessageChannel#receive(String)}</li>
 *   <li>连接关闭 → 关闭 channel</li>
 * </ul>
 * 不维护任何会话内部状态。
 */
@Slf4j
public class AiChatWebSocketHandler extends TextWebSocketHandler {

    private static final String CHANNEL_KEY = "WS_CHANNEL";

    private final AgentExecutorFactory agentExecutorFactory;

    public AiChatWebSocketHandler(AgentExecutorFactory agentExecutorFactory) {
        this.agentExecutorFactory = agentExecutorFactory;
    }

    @Override
    public void afterConnectionEstablished(@NonNull WebSocketSession session) throws Exception {
        super.afterConnectionEstablished(session);
        WebSocketMessageChannel channel = new WebSocketMessageChannel(session);
        agentExecutorFactory.create(channel);
        session.getAttributes().put(CHANNEL_KEY, channel);
    }

    @Override
    protected void handleTextMessage(@NonNull WebSocketSession wsSession, @NonNull TextMessage message) {
        WebSocketMessageChannel channel =
                (WebSocketMessageChannel) wsSession.getAttributes().get(CHANNEL_KEY);
        if (channel != null) {
            channel.receive(message.getPayload());
        }
    }

    @Override
    public void handleTransportError(@NonNull WebSocketSession session, @NonNull Throwable exception) {
        log.error("WebSocket 传输异常", exception);
    }

    @Override
    public void afterConnectionClosed(@NonNull WebSocketSession session, @NonNull CloseStatus status) {
        log.info("WebSocket 连接已关闭，状态: {}", status);
        WebSocketMessageChannel channel =
                (WebSocketMessageChannel) session.getAttributes().remove(CHANNEL_KEY);
        if (channel != null) {
            channel.close();
        }
    }
}
