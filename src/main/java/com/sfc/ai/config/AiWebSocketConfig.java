package com.sfc.ai.config;

import com.sfc.ai.controller.AiChatSocketHandler;
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

    private final AiChatSocketHandler aiChatSocketHandler;

    public AiWebSocketConfig(AiChatSocketHandler aiChatSocketHandler) {
        this.aiChatSocketHandler = aiChatSocketHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(aiChatSocketHandler, "/api/ai/wschat")
                .setAllowedOriginPatterns("*");
    }
}
