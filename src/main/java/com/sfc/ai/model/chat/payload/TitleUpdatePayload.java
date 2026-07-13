package com.sfc.ai.model.chat.payload;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * TITLE_UPDATE 消息类型的专属数据，用于向客户端推送对话标题。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TitleUpdatePayload {
    /**
     * 生成的对话标题
     */
    private String title;

    /**
     * 对应的会话 ID
     */
    private String conversationId;
}
