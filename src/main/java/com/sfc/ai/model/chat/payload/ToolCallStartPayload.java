package com.sfc.ai.model.chat.payload;

import lombok.Data;

/**
 * TOOL_CALL_START 消息类型的专属数据，包含 LLM 发起的工具调用 id、名称和参数。
 */
@Data
public class ToolCallStartPayload {
    /**
     * 工具调用 id，用于关联 TOOL_CALL_START 与 TOOL_CALL_END
     */
    private String id;

    /**
     * 工具名称
     */
    private String name;

    /**
     * 工具参数（JSON 格式）
     */
    private String arguments;
}
