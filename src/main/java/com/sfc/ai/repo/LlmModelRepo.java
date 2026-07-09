package com.sfc.ai.repo;

import com.sfc.ai.model.po.LlmModel;
import com.xiaotao.saltedfishcloud.dao.BaseRepo;

import java.util.List;

/**
 * 模型数据仓库
 */
public interface LlmModelRepo extends BaseRepo<LlmModel> {

    /**
     * 根据提供商 ID 查询关联的模型列表
     *
     * @param llmProviderId 提供商 ID
     * @return 模型列表
     */
    List<LlmModel> findByLlmProviderId(Long llmProviderId);
}
