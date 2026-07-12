package com.sfc.ai.repo;

import com.sfc.ai.model.po.AiChatMemory;
import com.xiaotao.saltedfishcloud.dao.BaseRepo;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 聊天记忆数据仓库。
 */
public interface AiChatMemoryRepo extends BaseRepo<AiChatMemory> {

    /**
     * 按会话 ID 查询所有消息（按 ID 升序排列）。
     *
     * @param conversationId 会话 ID
     * @return 消息列表
     */
    List<AiChatMemory> findByConversationIdOrderById(String conversationId);

    /**
     * 删除指定会话的所有消息。
     *
     * @param conversationId 会话 ID
     */
    @Transactional
    @Modifying
    @Query("DELETE FROM AiChatMemory t WHERE t.conversationId = :conversationId")
    int deleteByConversationId(@Param("conversationId") String conversationId);

    /**
     * 查询所有不重复的会话 ID。
     *
     * @return 会话 ID 列表
     */
    @Query("SELECT DISTINCT t.conversationId FROM AiChatMemory t")
    List<String> findDistinctConversationIds();
}
