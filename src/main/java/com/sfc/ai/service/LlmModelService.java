package com.sfc.ai.service;

import com.sfc.ai.model.po.LlmModel;
import com.xiaotao.saltedfishcloud.service.CrudService;

import java.util.List;

/**
 * 模型服务接口
 */
public interface LlmModelService extends CrudService<LlmModel> {

    /**
     * 根据提供商 ID 查询模型列表
     *
     * @param providerId 提供商 ID
     * @return 模型列表
     */
    List<LlmModel> findByProviderId(Long providerId);

    /**
     * 根据提供商 ID 删除所有关联模型
     *
     * @param providerId 提供商 ID
     */
    void deleteByProviderId(Long providerId);
}
