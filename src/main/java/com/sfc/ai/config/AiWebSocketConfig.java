package com.sfc.ai.config;

import com.sfc.ai.controller.AiChatWebSocketHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * AI 模块 WebSocket 配置。
 * <p>
 * 使用 {@link EnableWebSocket} 启用 Spring WebSocket 支持，
 * 并注册 AI 聊天相关的 WebSocket 处理器。
 * 遵循 Spring Framework 官方推荐的 {@link WebSocketConfigurer} 方式配置。
 */
@Configuration
@EnableWebSocket
public class AiWebSocketConfig implements WebSocketConfigurer {

    private final AiChatWebSocketHandler aiChatWebSocketHandler;

    public AiWebSocketConfig(AiChatWebSocketHandler aiChatWebSocketHandler) {
        this.aiChatWebSocketHandler = aiChatWebSocketHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(aiChatWebSocketHandler, "/api/ai/wschat")
                // ⚠️ 必须挂握手拦截器：微服务侧没有 Spring Security，WS 会话的 Principal 为空，
                //    且消息回调线程没有 HTTP 请求上下文；只能在【握手时】把网关注入的 X-Sfc-Uid
                //    快照进会话属性，否则会话内拿不到用户（详见 SfcUidHandshakeInterceptor 的类注释）。
                .addInterceptors(new SfcUidHandshakeInterceptor())
                .setAllowedOriginPatterns("*");
    }
}
