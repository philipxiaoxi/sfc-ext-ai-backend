package com.sfc.ai.constant;

/**
 * 用户消息类型
 */
public enum UserMessageType {

    /** 开启会话 */
    START_SESSION,

    /** 发送给 LLM 的普通用户消息 */
    CHAT,

    /** 用户对 LLM 发起的工具调用的确认回应 */
    TOOL_ACK,

    /** 停止请求 */
    STOP
}
