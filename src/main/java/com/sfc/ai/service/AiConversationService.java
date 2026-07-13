package com.sfc.ai.service;

import com.sfc.ai.model.po.AiConversation;
import com.xiaotao.saltedfishcloud.service.CrudService;

import java.util.List;

/**
 * AI 对话记录服务接口。
 */
public interface AiConversationService extends CrudService<AiConversation> {
    /**
     * 按用户查询所有对话记录，按更新时间降序排列。
     *
     * @param uid 用户 ID
     * @return 对话记录列表
     */
    List<AiConversation> findByUidOrderByUpdateAtDesc(Long uid);

    /**
     * 判断指定会话 ID 是否已存在。
     *
     * @param conversationId 会话 ID
     * @return 是否存在
     */
    boolean existsByConversationId(String conversationId);
}
