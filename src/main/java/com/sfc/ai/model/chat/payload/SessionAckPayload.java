package com.sfc.ai.model.chat.payload;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * SESSION_ACK 消息类型的专属数据，用于向客户端确认会话 ID。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SessionAckPayload {
    /**
     * 服务端确认的会话 ID
     */
    private String sessionId;
}
