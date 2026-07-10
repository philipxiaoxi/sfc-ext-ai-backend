package com.sfc.ai.controller;

import com.xiaotao.saltedfishcloud.model.po.UserPrincipal;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * 处理 AI 消息的 WebSocketHandler
 */
@Slf4j
public class AiChatSocketHandler extends TextWebSocketHandler {

    @Override
    public void afterConnectionEstablished(@NonNull WebSocketSession session) throws Exception {
        super.afterConnectionEstablished(session);
        UserPrincipal user = getUser(session);
        System.out.println(this);
        log.info("WebSocket 连接已建立，用户: {}", user != null ? user.getUsername() : "未知");
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        String payload = message.getPayload();
        log.debug("收到消息: {}", payload);
        session.sendMessage(new TextMessage("> " + payload));
    }

    @Override
    public void handleTransportError(@NonNull WebSocketSession session, @NonNull Throwable exception) {
        log.error("WebSocket 传输异常", exception);
    }

    @Override
    public void afterConnectionClosed(@NonNull WebSocketSession session, @NonNull CloseStatus status) {
        log.info("WebSocket 连接已关闭，状态: {}", status);
    }

    /**
     * 从 WebSocket 会话中获取当前登录用户。
     *
     * @param session WebSocket 会话
     * @return 用户主体，如果未认证则返回 null
     */
    private UserPrincipal getUser(WebSocketSession session) {
        if (session.getPrincipal() instanceof UsernamePasswordAuthenticationToken token
                && token.getPrincipal() instanceof UserPrincipal user) {
            return user;
        }
        return null;
    }
}
