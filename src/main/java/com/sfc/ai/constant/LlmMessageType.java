package com.sfc.ai.constant;

/**
 * 大模型回应的消息类型
 */
public enum LlmMessageType {
    /** 会话开始确认，表示接收了START_SESSION，并响应本次确定的会话id */
    SESSION_ACK,

    /** 工具调用开始 */
    TOOL_CALL_START,

    /** 工具调用结束 */
    TOOL_CALL_END,

    /** 工具调用告知 */
    TOOL_CALL,

    /** 工具调用请求，需要用户回应 */
    TOOL_CALL_REQ,

    /** 思考/回复纯文本消息 */
    TEXT,

    /** 对话标题更新 */
    TITLE_UPDATE,

    /**已停止回应标记 */
    DONE,

    /** 错误 */
    ERROR
}
