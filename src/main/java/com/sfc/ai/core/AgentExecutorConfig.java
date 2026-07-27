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

    /**
     * 是否完全授权模式，开启后敏感工具直接执行无需用户批准。
     */
    private boolean fullAuthMode;
}
