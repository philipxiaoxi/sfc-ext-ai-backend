package com.sfc.ai.repo;

import com.sfc.ai.model.po.AiConversation;
import com.xiaotao.saltedfishcloud.dao.BaseRepo;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * AI 对话记录数据仓库。
 */
public interface AiConversationRepo extends BaseRepo<AiConversation> {
    /**
     * 判断指定会话 ID 是否已存在。
     *
     * @param conversationId 会话 ID
     * @return 是否存在
     */
    boolean existsByConversationId(String conversationId);

    /**
     * 按会话 ID 查询对话记录（conversationId 具有唯一索引）。
     *
     * @param conversationId 会话 ID
     * @return 对话记录
     */
    Optional<AiConversation> findByConversationId(String conversationId);

    /**
     * 按用户查询所有对话记录，按更新时间降序排列。
     *
     * @param uid 用户 ID
     * @return 对话记录列表
     */
    @Query("SELECT t FROM AiConversation t WHERE t.uid = :uid ORDER BY t.updateAt DESC")
    List<AiConversation> findByUidOrderByUpdateAtDesc(@Param("uid") Long uid);
}
