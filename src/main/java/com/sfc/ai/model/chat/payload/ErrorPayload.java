package com.sfc.ai.model.chat.payload;

import lombok.Data;

/**
 * ERROR 消息类型的专属数据。
 */
@Data
public class ErrorPayload {
    /**
     * 错误描述
     */
    private String message;
}
