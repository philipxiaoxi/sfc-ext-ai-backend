package com.sfc.ai.model.chat.message;

import com.sfc.ai.constant.LlmMessageType;
import lombok.Data;

import java.util.Map;

/**
 * 大模型的回复消息
 */
@Data
public class LlmResponse {
    /**
     * 消息类型
     */
    private LlmMessageType type;

    /**
     * 消息内容
     */
    private Object message;

    /**
     * 元数据
     */
    private Map<String, Object> meta;
}
