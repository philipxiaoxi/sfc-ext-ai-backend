package com.sfc.ai.config;

import com.sfc.cloud.trust.SfcTrustHeaders;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;

/**
 * WebSocket 握手拦截器：把网关注入的 uid 快照进会话属性。
 */
public class SfcUidHandshakeInterceptor implements HandshakeInterceptor {

    /**
     * 会话属性键：握手时快照到的 uid 原文（{@code String}）。
     *
     * <p>握手请求里没有该头（例如未经过网关的直连、或匿名请求）时不会写入该属性，
     * 读取方应按「未登录」处理，不要当作 0。</p>
     */
    public static final String ATTR_UID = "SFC_UID";

    /**
     * 握手前：把 {@code X-Sfc-Uid} 快照进会话属性。
     *
     * @param request    握手请求
     * @param response   握手响应
     * @param wsHandler  目标处理器
     * @param attributes 会话属性（将被写入 {@link #ATTR_UID}）
     * @return 恒为 {@code true}：身份缺失不阻断握手，交由业务层按「未登录」处理
     */
    @Override
    public boolean beforeHandshake(ServerHttpRequest request,
                                   ServerHttpResponse response,
                                   WebSocketHandler wsHandler,
                                   Map<String, Object> attributes) {
        String uid = request.getHeaders().getFirst(SfcTrustHeaders.UID);
        if ((uid == null || uid.isBlank()) && request instanceof ServletServerHttpRequest servletRequest) {
            // 个别容器/网关实现下自定义头不会进入 ServerHttpRequest 的 headers，回退读原生请求
            uid = servletRequest.getServletRequest().getHeader(SfcTrustHeaders.UID);
        }
        if (uid != null && !uid.isBlank()) {
            attributes.put(ATTR_UID, uid.trim());
        }
        return true;
    }

    /**
     * 握手后：无需处理。
     *
     * @param request    握手请求
     * @param response   握手响应
     * @param wsHandler  目标处理器
     * @param exception  握手期异常；成功时为 {@code null}
     */
    @Override
    public void afterHandshake(ServerHttpRequest request,
                               ServerHttpResponse response,
                               WebSocketHandler wsHandler,
                               Exception exception) {
        // 无需处理
    }
}
