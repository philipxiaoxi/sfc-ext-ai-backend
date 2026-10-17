package com.sfc.ai.core.channel;

import com.sfc.ai.config.SfcUidHandshakeInterceptor;
import com.sfc.ai.constant.LlmMessageType;
import com.sfc.ai.identity.SfcCurrentUserSupport;
import com.sfc.ai.model.chat.message.LlmResponse;
import com.sfc.ai.model.chat.message.UserRequest;
import com.sfc.ai.model.chat.payload.ErrorPayload;
import com.sfc.common.model.UserPrincipal;
import com.sfc.common.utils.SfcMapperHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.security.Principal;
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
    private Runnable closeHandler;

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
            UserRequest request = SfcMapperHolder.parseJson(rawPayload, UserRequest.class);
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
        queue.add(new TextMessage(SfcMapperHolder.toJsonNoEx(response)));
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
    public void onClose(Runnable handler) {
        this.closeHandler = handler;
    }

    @Override
    public void close() {
        if (closeHandler != null) {
            try {
                closeHandler.run();
            } catch (Exception e) {
                log.warn("执行通道关闭回调时发生异常", e);
            }
        }
        try {
            session.close();
        } catch (IOException e) {
            log.warn("关闭 WebSocket 会话失败", e);
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p><b>微服务口径的身份来源：</b>原插件期从 Spring Security 的
     * {@code UsernamePasswordAuthenticationToken} 取用户；本服务没有 Spring Security，
     * 身份只来自网关在<b>握手请求</b>上注入的信任头 {@code X-Sfc-Uid}。</p>
     *
     * <p>为什么读握手头而不是请求上下文：WebSocket 只有握手那一刻是普通 HTTP 请求
     * （网关注入信任头、{@code RequestContextHolder} 可用），此后每条消息都跑在
     * 独立的容器线程上、没有请求上下文；唯一随连接存活的身份载体就是
     * {@link WebSocketSession#getHandshakeHeaders()}。</p>
     *
     * <p>降级：握手头缺失 / 为匿名 {@code "0"} / 非法时返回 {@code null}（等同原实现的
     * 「无法认证」），username 与 admin 另经用户档案回源补齐，取不到时为占位用户名 + 非管理员。</p>
     *
     * @return 当前连接的用户主体；无法确定身份时返回 {@code null}
     */
    @Override
    public UserPrincipal getUser() {
        // 首选：握手拦截器（SfcUidHandshakeInterceptor）在握手那一刻直接读原始 ServerHttpRequest
        // 写入的会话属性。这是最可靠的一路——它对「自定义头未进入 HandshakeHeaders」的容器/网关
        // 差异还有一层 ServletServerHttpRequest 回退。
        Object snapshotUid = session.getAttributes().get(SfcUidHandshakeInterceptor.ATTR_UID);
        if (snapshotUid instanceof String rawUid) {
            UserPrincipal fromSnapshot =
                    SfcCurrentUserSupport.resolveUser(SfcCurrentUserSupport.parseUidHeader(rawUid));
            if (fromSnapshot != null) {
                return fromSnapshot;
            }
        }
        // 兜底：握手头随连接存活，是唯一随连接存活的身份载体；即使拦截器未生效也能工作。
        Principal principal = session.getPrincipal();
        if (principal instanceof UserPrincipal user) {
            return user;
        }
        return SfcCurrentUserSupport.resolveUserFromHeaders(session.getHandshakeHeaders());
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
