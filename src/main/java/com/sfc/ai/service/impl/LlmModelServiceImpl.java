package com.sfc.ai.service.impl;

import com.sfc.ai.model.po.LlmModel;
import com.sfc.ai.repo.LlmModelRepo;
import com.sfc.ai.service.LlmModelService;
import com.sfc.cloud.data.service.SfcCrudServiceImpl;

import java.util.Collection;
import java.util.List;

/**
 * 模型服务实现
 */
public class LlmModelServiceImpl extends SfcCrudServiceImpl<LlmModel, LlmModelRepo> implements LlmModelService {

    @Override
    public List<LlmModel> findByProviderId(Long providerId) {
        return repository.findByLlmProviderId(providerId);
    }

    @Override
    public List<LlmModel> findByProviderIds(Collection<Long> providerIds) {
        return repository.findByLlmProviderIdIn(providerIds);
    }

    @Override
    public void deleteByProviderId(Long providerId) {
        List<LlmModel> models = repository.findByLlmProviderId(providerId);
        repository.deleteAll(models);
    }
}
