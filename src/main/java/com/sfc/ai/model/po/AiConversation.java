package com.sfc.ai.model.po;

import com.xiaotao.saltedfishcloud.model.template.AuditModel;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/**
 * AI 对话记录。
 * 记录每个对话的标题、会话 ID 和所属用户。
 */
@Getter
@Setter
@Entity
@Table(indexes = {
        @Index(name = "idx_ai_conversation_uid", columnList = "uid"),
        @Index(name = "idx_ai_conversation_conversation_id", columnList = "conversationId", unique = true)
})
public class AiConversation extends AuditModel {
    /** 会话 ID（对应 WebSocket 协议的 sessionId） */
    private String conversationId;

    /** 对话标题（由 LLM 自动生成，不超过 20 字） */
    private String title;
}
