package com.sfc.ai.core.channel;

import com.sfc.ai.constant.LlmMessageType;
import com.sfc.ai.model.chat.message.LlmResponse;
import com.sfc.ai.model.chat.message.UserRequest;
import com.sfc.ai.model.chat.payload.ErrorPayload;
import com.xiaotao.saltedfishcloud.model.po.UserPrincipal;
import com.xiaotao.saltedfishcloud.utils.MapperHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * WebSocket 实现的 {@link MessageChannel}。
 * <p>
 * 包装 {@link WebSocketSession}，内部使用 {@link ConcurrentLinkedQueue} +
 * 单线程排空机制确保多线程并发发送时数据不会交错。
 * <p>
 * 入站消息由 {@link #receive(String)} 接收原始 JSON 字符串，
 * 反序列化后交给已注册的 {@link MessageHandler} 处理。
 */
@Slf4j
public class WebSocketMessageChannel implements MessageChannel {

    private final WebSocketSession session;
    private final ConcurrentLinkedQueue<TextMessage> queue = new ConcurrentLinkedQueue<>();
    private volatile boolean draining = false;
    private MessageHandler handler;

    public WebSocketMessageChannel(WebSocketSession session) {
        this.session = session;
    }

    /**
     * 接收一条原始消息负载，反序列化后交给已注册的处理器。
     *
     * @param rawPayload WebSocket 消息的 JSON 字符串
     */
    public void receive(String rawPayload) {
        try {
            UserRequest request = MapperHolder.parseJson(rawPayload, UserRequest.class);
            if (handler != null) {
                handler.onMessage(request);
            }
        } catch (Exception e) {
            log.warn("消息反序列化失败: {}", rawPayload, e);
            sendError("消息格式错误，无法解析");
        }
    }

    @Override
    public void send(LlmMessageType type, Object data) {
        LlmResponse response = new LlmResponse();
        response.setType(type);
        response.setData(data);
        queue.add(new TextMessage(MapperHolder.toJsonNoEx(response)));
        tryDrain();
    }

    @Override
    public void sendError(String message) {
        ErrorPayload errorPayload = new ErrorPayload();
        errorPayload.setMessage(message);
        send(LlmMessageType.ERROR, errorPayload);
    }

    @Override
    public void onMessage(MessageHandler handler) {
        this.handler = handler;
    }

    @Override
    public void close() {
        try {
            session.close();
        } catch (IOException e) {
            log.warn("关闭 WebSocket 会话失败", e);
        }
    }

    @Override
    public UserPrincipal getUser() {
        if (session.getPrincipal() instanceof UsernamePasswordAuthenticationToken token
                && token.getPrincipal() instanceof UserPrincipal user) {
            return user;
        }
        return null;
    }

    private void tryDrain() {
        synchronized (this) {
            if (draining) return;
            draining = true;
        }
        try {
            while (true) {
                TextMessage msg = queue.poll();
                if (msg == null) break;
                try {
                    session.sendMessage(msg);
                } catch (IOException e) {
                    log.error("WebSocket 消息发送失败", e);
                    break;
                }
            }
        } finally {
            synchronized (this) {
                draining = false;
            }
            if (!queue.isEmpty()) {
                tryDrain();
            }
        }
    }
}
