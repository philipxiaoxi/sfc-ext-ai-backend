package com.sfc.ai.model.chat.payload;

import lombok.Data;

/**
 * TOOL_CALL_END 消息类型的专属数据，包含工具调用 id、名称、执行结果和状态。
 */
@Data
public class ToolCallEndPayload {
    /**
     * 工具调用 id，用于关联 TOOL_CALL_START 与 TOOL_CALL_END
     */
    private String id;

    /**
     * 工具名称
     */
    private String name;

    /**
     * 工具执行结果（{@link ToolCallStatus#SUCCESS} 时有值）
     */
    private String result;

    /**
     * 工具调用执行状态：SUCCESS / ERROR / CANCELLED
     */
    private ToolCallStatus status;

    /**
     * 错误信息（{@link ToolCallStatus#ERROR} 或 {@link ToolCallStatus#CANCELLED} 时有值）
     */
    private String errorMessage;
}
