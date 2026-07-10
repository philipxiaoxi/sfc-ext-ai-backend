package com.sfc.ai.controller;

import com.sfc.ai.constant.LlmMessageType;
import com.sfc.ai.constant.UserMessageType;
import com.sfc.ai.model.chat.message.LlmResponse;
import com.sfc.ai.model.chat.message.UserRequest;
import com.xiaotao.saltedfishcloud.model.po.UserPrincipal;
import com.xiaotao.saltedfishcloud.utils.MapperHolder;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * AI 聊天 WebSocket 处理器。
 * <p>
 * 消息格式为纯文本 JSON，使用 {@link MapperHolder} 进行序列化/反序列化。
 * 接收的消息反序列化为 {@link UserRequest}，发送的消息从 {@link LlmResponse} 序列化。
 * <p>
 * 协议约束：第一条消息必须为 {@link UserMessageType#START_SESSION}，
 * 且每个 WebSocket 连接仅允许发送一次该类型消息。
 */
@Slf4j
public class AiChatSocketHandler extends TextWebSocketHandler {

    private static final String SESSION_STARTED_KEY = "SESSION_STARTED";

    @Override
    public void afterConnectionEstablished(@NonNull WebSocketSession session) throws Exception {
        super.afterConnectionEstablished(session);
        UserPrincipal user = getUser(session);
        log.info("WebSocket 连接已建立，用户: {}", user != null ? user.getUsername() : "未知");
    }

    @Override
    protected void handleTextMessage(@NonNull WebSocketSession session, @NonNull TextMessage message) throws Exception {
        String payload = message.getPayload();
        log.debug("收到消息: {}", payload);

        UserRequest userRequest;
        try {
            userRequest = MapperHolder.parseJson(payload, UserRequest.class);
        } catch (Exception e) {
            log.warn("消息反序列化失败: {}", payload, e);
            sendError(session, "消息格式错误，无法解析");
            return;
        }

        if (userRequest.getType() == null) {
            sendError(session, "消息类型不能为空");
            return;
        }

        UserMessageType type = userRequest.getType();
        boolean sessionStarted = isSessionStarted(session);

        if (type == UserMessageType.START_SESSION) {
            if (sessionStarted) {
                sendError(session, "START_SESSION 仅允许发送一次");
                return;
            }
            markSessionStarted(session);
            LlmResponse response = new LlmResponse();
            response.setType(LlmMessageType.TEXT);
            response.setMessage("会话已开启");
            session.sendMessage(new TextMessage(MapperHolder.toJson(response)));
            return;
        }

        if (!sessionStarted) {
            sendError(session, "请先发送 START_SESSION 消息开启会话");
            return;
        }

        switch (type) {
            case CHAT -> handleChat(session, userRequest);
            case TOOL_ACK -> handleToolAck(session, userRequest);
            case STOP -> handleStop(session);
            default -> sendError(session, "未知消息类型: " + type);
        }
    }

    /**
     * 处理聊天消息。
     */
    private void handleChat(WebSocketSession session, UserRequest request) throws Exception {
        // TODO: 接入 LLM 服务
        LlmResponse response = new LlmResponse();
        response.setType(LlmMessageType.TEXT);
        response.setMessage("收到消息: " + request.getMessage());
        session.sendMessage(new TextMessage(MapperHolder.toJson(response)));
    }

    /**
     * 处理工具调用确认。
     */
    private void handleToolAck(WebSocketSession session, UserRequest request) throws Exception {
        // TODO: 处理工具调用确认
        LlmResponse response = new LlmResponse();
        response.setType(LlmMessageType.TEXT);
        response.setMessage("工具确认已收到");
        session.sendMessage(new TextMessage(MapperHolder.toJson(response)));
    }

    /**
     * 处理停止请求。
     */
    private void handleStop(WebSocketSession session) throws Exception {
        // TODO: 停止 LLM 响应
        LlmResponse response = new LlmResponse();
        response.setType(LlmMessageType.DONE);
        response.setMessage("已停止");
        session.sendMessage(new TextMessage(MapperHolder.toJson(response)));
    }

    /**
     * 发送错误消息。
     */
    private void sendError(WebSocketSession session, String errorMessage) throws Exception {
        LlmResponse response = new LlmResponse();
        response.setType(LlmMessageType.ERROR);
        response.setMessage(errorMessage);
        session.sendMessage(new TextMessage(MapperHolder.toJson(response)));
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
     * 判断当前会话是否已通过 START_SESSION 初始化。
     */
    private boolean isSessionStarted(WebSocketSession session) {
        return Boolean.TRUE.equals(session.getAttributes().get(SESSION_STARTED_KEY));
    }

    /**
     * 标记会话已初始化。
     */
    private void markSessionStarted(WebSocketSession session) {
        session.getAttributes().put(SESSION_STARTED_KEY, Boolean.TRUE);
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
