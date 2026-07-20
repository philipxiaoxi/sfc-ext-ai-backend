package com.sfc.ai.model.chat.payload;

/**
 * 工具调用执行状态。
 */
public enum ToolCallStatus {
    /** 工具执行成功 */
    SUCCESS,
    /** 工具执行失败（抛出异常） */
    ERROR,
    /** 被用户 STOP 主动中断 */
    CANCELLED
}
