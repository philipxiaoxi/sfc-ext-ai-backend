package com.sfc.ai.service.impl;

import com.sfc.ai.model.po.LlmModel;
import com.sfc.ai.repo.LlmModelRepo;
import com.sfc.ai.service.LlmModelService;
import com.xiaotao.saltedfishcloud.service.CrudServiceImpl;

import java.util.List;

/**
 * 模型服务实现
 */
public class LlmModelServiceImpl extends CrudServiceImpl<LlmModel, LlmModelRepo> implements LlmModelService {

    @Override
    public List<LlmModel> findByProviderId(Long providerId) {
        return repository.findByLlmProviderId(providerId);
    }

    @Override
    public void deleteByProviderId(Long providerId) {
        List<LlmModel> models = repository.findByLlmProviderId(providerId);
        repository.deleteAll(models);
    }
}
