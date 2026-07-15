package com.sfc.ai.service;

import com.sfc.ai.model.po.AiChatMemory;
import com.xiaotao.saltedfishcloud.service.CrudService;

import java.util.List;

/**
 * AI 聊天记忆服务接口。
 */
public interface AiChatMemoryService extends CrudService<AiChatMemory> {

    /**
     * 按会话 ID 查询所有消息（按 ID 升序排列）。
     *
     * @param conversationId 会话 ID
     * @return 消息列表
     */
    List<AiChatMemory> findByConversationIdOrderById(String conversationId);
}
