package com.sfc.ai.service.impl;

import com.sfc.ai.model.po.AiChatMemory;
import com.sfc.ai.repo.AiChatMemoryRepo;
import com.sfc.ai.service.AiChatMemoryService;
import com.xiaotao.saltedfishcloud.service.CrudServiceImpl;

import java.util.List;

/**
 * AI 聊天记忆服务实现。
 */
public class AiChatMemoryServiceImpl extends CrudServiceImpl<AiChatMemory, AiChatMemoryRepo> implements AiChatMemoryService {

    @Override
    public List<AiChatMemory> findByConversationIdOrderById(String conversationId) {
        return repository.findByConversationIdOrderById(conversationId);
    }
}
