package com.sfc.ai.constant;

/**
 * 敏感工具调用状态。
 */
public enum SensitiveToolCallStatus {

    /**
     * 完全授权模式下由 LLM 直接执行的敏感工具调用（无需用户批准）。
     */
    AUTO_EXECUTED,

    /**
     * 普通授权模式下用户批准后执行的敏感工具调用。
     */
    APPROVED_EXECUTED,

    /**
     * 用户拒绝执行的敏感工具调用。
     */
    REJECTED
}
