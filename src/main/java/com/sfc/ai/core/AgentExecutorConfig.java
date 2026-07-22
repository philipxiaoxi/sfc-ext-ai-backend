package com.sfc.ai.core;

import lombok.Data;

/**
 * Agent 执行器配置。
 */
@Data
public class AgentExecutorConfig {
    /**
     * 是否自动根据首条消息生成对话标题，默认 true
     */
    private boolean autoGenerateTitle = true;
}
