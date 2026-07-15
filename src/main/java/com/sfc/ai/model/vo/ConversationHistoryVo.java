package com.sfc.ai.model.vo;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * 对话历史响应 VO。
 * <p>
 * 包含会话元信息及其消息列表，前端可直接用于渲染完整对话。
 */
@Getter
@Setter
public class ConversationHistoryVo {

    /** 会话 ID（对应 WebSocket 协议的 sessionId） */
    private String conversationId;

    /** 对话标题 */
    private String title;

    /** 历史消息列表（按发生顺序排列） */
    private List<HistoryMessageVo> messages;
}
