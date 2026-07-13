package com.sfc.ai.service.impl;

import com.sfc.ai.model.po.AiConversation;
import com.sfc.ai.repo.AiConversationRepo;
import com.sfc.ai.service.AiConversationService;
import com.xiaotao.saltedfishcloud.service.CrudServiceImpl;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * AI 对话记录服务实现。
 */
@Service
public class AiConversationServiceImpl extends CrudServiceImpl<AiConversation, AiConversationRepo> implements AiConversationService {

    @Override
    public List<AiConversation> findByUidOrderByUpdateAtDesc(Long uid) {
        return repository.findByUidOrderByUpdateAtDesc(uid);
    }

    @Override
    public boolean existsByConversationId(String conversationId) {
        return repository.existsByConversationId(conversationId);
    }
}
