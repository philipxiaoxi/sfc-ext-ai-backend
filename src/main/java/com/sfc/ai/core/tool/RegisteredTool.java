package com.sfc.ai.core.tool;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 通过 REGISTER_TOOL 消息注册的工具元信息。
 */
@Data
@AllArgsConstructor
public class RegisteredTool {
    private String name;
    private String description;
    private String inputSchema;
}
