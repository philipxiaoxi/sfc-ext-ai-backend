package com.sfc.ai.model.chat.payload;

import lombok.Data;

/**
 * START_SESSION 消息类型的专属数据。
 * <p>
 * 可根据后续需求添加会话初始化时的配置字段。
 */
@Data
public class StartSessionPayload {
    /**
     * 可选的会话 ID。若客户端传入则使用该值，否则由服务端生成 UUID。
     */
    private String sessionId;
}
