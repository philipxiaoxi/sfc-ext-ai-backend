package com.sfc.ai.model.chat.session;

import com.xiaotao.saltedfishcloud.model.po.UserPrincipal;
import lombok.Getter;

/**
 * AI 聊天会话状态。
 * <p>
 * 管理与单次对话相关的业务状态，独立于传输层实现。
 */
@Getter
public class ChatSession {
    private final String sessionId;
    private final UserPrincipal user;
    private boolean firstChat;

    /**
     * @param sessionId 会话 ID
     * @param user      用户主体
     * @param firstChat 是否为全新对话（不存在于数据库中），首次 CHAT 将触发标题生成
     */
    public ChatSession(String sessionId, UserPrincipal user, boolean firstChat) {
        this.sessionId = sessionId;
        this.user = user;
        this.firstChat = firstChat;
    }

    /** 标记首次对话已处理。 */
    public void markFirstChatDone() {
        this.firstChat = false;
    }
}
