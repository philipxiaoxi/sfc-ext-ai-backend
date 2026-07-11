package com.sfc.ai.model.chat.payload;

import lombok.Data;

/**
 * TOOL_CALL 消息类型的专属数据，包含工具调用的名称、参数和返回值。
 */
@Data
public class ToolCallPayload {
    /**
     * 工具名称
     */
    private String name;

    /**
     * 工具参数（JSON 格式）
     */
    private String arguments;

    /**
     * 工具执行结果
     */
    private String result;
}
