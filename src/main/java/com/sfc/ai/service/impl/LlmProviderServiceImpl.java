package com.sfc.ai.service.impl;

import com.sfc.ai.model.po.LlmProvider;
import com.sfc.ai.repo.LlmProviderRepo;
import com.sfc.ai.service.LlmProviderService;
import com.xiaotao.saltedfishcloud.service.CrudServiceImpl;

/**
 * 模型提供商服务实现
 */
public class LlmProviderServiceImpl extends CrudServiceImpl<LlmProvider, LlmProviderRepo> implements LlmProviderService {
}
