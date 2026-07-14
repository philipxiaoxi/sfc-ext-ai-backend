package com.sfc.ai.model.chat.payload;

import lombok.Data;
import java.util.Map;

/**
 * TOOL_ACK 消息的负载数据。
 * <p>
 * 客户端对 TOOL_CALL_REQ 的响应，包含工具调用的标识和执行结果。
 */
@Data
public class ToolCallAckPayload {
    private String id;
    private String name;
    private Map<String, Object> arguments;
    private String result;
}
