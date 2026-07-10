package com.sfc.ai.repo;

import com.sfc.ai.model.po.LlmProvider;
import com.xiaotao.saltedfishcloud.dao.BaseRepo;

import java.util.Collection;
import java.util.List;

/**
 * 模型提供商数据仓库
 */
public interface LlmProviderRepo extends BaseRepo<LlmProvider> {

    /**
     * 根据多个用户 ID 查询提供商列表
     *
     * @param uids 用户 ID 集合
     * @return 提供商列表
     */
    List<LlmProvider> findByUidIn(Collection<Long> uids);
}
