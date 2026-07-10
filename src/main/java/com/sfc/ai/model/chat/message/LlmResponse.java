package com.sfc.ai.model.chat.message;

import com.sfc.ai.constant.LlmMessageType;
import lombok.Data;

/**
 * 服务端回复消息。
 * <p>
 * 不同消息类型携带的数据不同，具体数据放在 {@link #data} 字段中，
 * 按 {@link #type} 转换为对应的 Payload 类使用。
 */
@Data
public class LlmResponse {
    /**
     * 消息类型
     */
    private LlmMessageType type;

    /**
     * 类型专属数据，由 handler 根据 type 设置对应的 Payload 对象
     */
    private Object data;
}
