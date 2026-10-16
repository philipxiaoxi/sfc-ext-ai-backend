package com.sfc.ai.service.impl;

import com.sfc.ai.model.po.LlmProvider;
import com.sfc.ai.repo.LlmProviderRepo;
import com.sfc.ai.service.LlmProviderService;
import com.sfc.cloud.data.service.SfcCrudServiceImpl;

import java.util.Collection;
import java.util.List;

/**
 * 模型提供商服务实现
 */
public class LlmProviderServiceImpl extends SfcCrudServiceImpl<LlmProvider, LlmProviderRepo> implements LlmProviderService {

    @Override
    public List<LlmProvider> findByUidIn(Collection<Long> uids) {
        return repository.findByUidIn(uids);
    }
}
