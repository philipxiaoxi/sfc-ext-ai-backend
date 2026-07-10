package com.sfc.ai.model.chat.payload;

import lombok.Data;

/**
 * CHAT 消息类型的专属数据。
 */
@Data
public class ChatPayload {
    /**
     * 模型 ID
     */
    private Long modelId;

    /**
     * 聊天内容
     */
    private String content;
}
