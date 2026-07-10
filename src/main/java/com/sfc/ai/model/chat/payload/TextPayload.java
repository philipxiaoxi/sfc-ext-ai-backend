package com.sfc.ai.model.chat.payload;

import lombok.Data;

/**
 * TEXT 消息类型的专属数据。
 */
@Data
public class TextPayload {
    /**
     * 文本内容
     */
    private String content;
}
