package com.sfc.ai.model.chat.message;

import com.sfc.ai.constant.UserMessageType;
import lombok.Data;

/**
 * 用户 WebSocket 消息请求体。
 * <p>
 * 不同消息类型携带的数据不同，具体数据放在 {@link #data} 字段中，
 * 按 {@link #type} 转换为对应的 Payload 类使用。
 */
@Data
public class UserRequest {
    /**
     * 消息类型
     */
    private UserMessageType type;

    /**
     * 类型专属数据，由 handler 根据 type 转换为具体 Payload 对象
     */
    private Object data;
}
