package com.sfc.ai.constant;

/**
 * 大模型回应的消息类型
 */
public enum LlmMessageType {
    /** 会话开始确认，表示接收了START_SESSION，并响应本次确定的会话id */
    SESSION_ACK,

    /** 工具调用告知 */
    TOOL_CALL,

    /** 工具调用请求，需要用户回应 */
    TOOL_CALL_REQ,

    /** 进入思考 */
    THINKING_START,

    /** 思考结束 */
    THINKING_END,

    /** 思考/回复纯文本消息 */
    TEXT,

    /**已停止回应标记 */
    DONE,

    /** 错误 */
    ERROR
}
