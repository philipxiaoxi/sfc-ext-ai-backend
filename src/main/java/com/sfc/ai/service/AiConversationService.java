package com.sfc.ai.service;

import com.sfc.ai.model.po.AiConversation;
import com.sfc.ai.model.vo.ConversationHistoryVo;
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

    /**
     * 按会话 ID 查询对话记录。
     *
     * @param conversationId 会话 ID
     * @return 对话记录，不存在时返回 null
     */
    AiConversation findByConversationId(String conversationId);

    /**
     * 获取指定会话的完整历史消息记录。
     * <p>
     * 将持久化的 {@link com.sfc.ai.model.po.AiChatMemory} 实体按发生顺序扁平化为前端可直接渲染的消息列表，
     * 包含用户消息、AI 助手思考内容与正文、工具调用及其结果。
     *
     * @param conversationId 会话 ID
     * @return 对话历史 VO
     * @throws com.xiaotao.saltedfishcloud.exception.JsonException 若会话不存在
     */
    ConversationHistoryVo getConversationMessages(String conversationId);
}
