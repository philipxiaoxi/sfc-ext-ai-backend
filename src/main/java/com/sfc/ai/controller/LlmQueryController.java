package com.sfc.ai.controller;

import com.sfc.ai.model.po.LlmModel;
import com.sfc.ai.model.po.LlmProvider;
import com.sfc.ai.model.vo.ProviderVo;
import com.sfc.ai.model.vo.ProviderWithModelsVo;
import com.sfc.ai.service.LlmModelService;
import com.sfc.ai.service.LlmProviderService;
import com.xiaotao.saltedfishcloud.model.json.JsonResult;
import com.xiaotao.saltedfishcloud.model.json.JsonResultImpl;
import com.xiaotao.saltedfishcloud.utils.SecureUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * AI 模型组合查询控制器
 */
@RestController
@RequestMapping("/api/ai/query")
public class LlmQueryController {

    @Autowired
    private LlmProviderService llmProviderService;

    @Autowired
    private LlmModelService llmModelService;

    /**
     * 查询所有公共（uid=0）和当前用户的模型提供商及其关联模型列表。
     * 响应中已排除 apiKey 等敏感信息字段。
     *
     * @return 提供商及其模型列表
     */
    @GetMapping("providersWithModels")
    public JsonResult<List<ProviderWithModelsVo>> getProvidersWithModels() {
        Long currentUid = SecureUtils.getCurrentUid();

        List<Long> uids = new ArrayList<>();
        uids.add(0L);
        if (currentUid != null) {
            uids.add(currentUid);
        }

        List<LlmProvider> providers = llmProviderService.findByUidIn(uids);

        if (providers.isEmpty()) {
            return JsonResultImpl.getInstance(List.of());
        }

        List<Long> providerIds = providers.stream()
                .map(LlmProvider::getId)
                .collect(Collectors.toList());

        List<LlmModel> models = llmModelService.findByProviderIds(providerIds);

        Map<Long, List<LlmModel>> modelMap = models.stream()
                .collect(Collectors.groupingBy(LlmModel::getLlmProviderId));

        List<ProviderWithModelsVo> result = providers.stream()
                .map(provider -> new ProviderWithModelsVo(
                        ProviderVo.from(provider),
                        modelMap.getOrDefault(provider.getId(), List.of())
                ))
                .collect(Collectors.toList());

        return JsonResultImpl.getInstance(result);
    }
}
