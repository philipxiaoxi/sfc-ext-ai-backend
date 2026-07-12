package com.sfc.ai.model.po;

import com.xiaotao.saltedfishcloud.model.template.AuditModel;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.springframework.ai.chat.messages.MessageType;

/**
 * AI 聊天记忆实体，用于持久化存储聊天消息。
 * <p>
 * 每个实体对应一条聊天消息，通过 {@link #conversationId} 关联到同一会话。
 * 消息内容、元数据及工具调用数据分别存储在独立的字段中。
 */
@Getter
@Setter
@Entity
@Table(indexes = {
        @Index(name = "idx_chat_memory_conversation_id", columnList = "conversationId"),
        @Index(name = "idx_chat_memory_uid", columnList = "uid")
})
public class AiChatMemory extends AuditModel {

    /**
     * 会话标识
     */
    private String conversationId;

    /**
     * 消息类型（USER、ASSISTANT、SYSTEM、TOOL）
     */
    @Enumerated(EnumType.STRING)
    private MessageType messageType;

    /**
     * 消息文本内容
     */
    @Lob
    private String content;

    /**
     * 消息元数据（JSON 格式）
     */
    @Lob
    private String messageMetadata;

    /**
     * 工具调用数据（JSON 格式，仅 ASSISTANT 和 TOOL 类型的消息使用）
     */
    @Lob
    private String toolCallData;

    /**
     * LLM 在产生这条对话消息时的思考内容
     */
    @Lob
    private String reasoningContent;
}
