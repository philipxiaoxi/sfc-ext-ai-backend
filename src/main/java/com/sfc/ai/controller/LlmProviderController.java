package com.sfc.ai.controller;

import com.sfc.ai.model.po.LlmProvider;
import com.sfc.ai.service.LlmProviderService;
import com.sfc.cloud.constant.SfcCommonError;
import com.sfc.cloud.exception.SfcJsonException;
import com.sfc.cloud.model.json.SfcJsonResult;
import com.xiaotao.saltedfishcloud.utils.SecureUtils;
import com.sfc.cloud.data.validation.SfcUIDValidator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

/**
 * 模型提供商管理控制器
 */
@RestController
@RequestMapping("/api/ai/provider")
public class LlmProviderController {

    @Autowired
    private LlmProviderService llmProviderService;

    /**
     * 查询当前用户的提供商列表
     */
    @GetMapping("list")
    public SfcJsonResult<?> list() {
        Long uid = SecureUtils.getCurrentUid();
        return SfcJsonResult.getInstance(llmProviderService.findByUid(uid));
    }

    /**
     * 根据 ID 查询提供商
     */
    @GetMapping("get")
    public SfcJsonResult<?> get(@RequestParam Long id) {
        LlmProvider provider = llmProviderService.findById(id);
        if (provider == null) {
            throw new SfcJsonException(SfcCommonError.RESOURCE_NOT_FOUND);
        }
        if (!SfcUIDValidator.validate(provider.getUid(), true)) {
            throw new SfcJsonException(SfcCommonError.SYSTEM_FORBIDDEN);
        }
        return SfcJsonResult.getInstance(provider);
    }

    /**
     * 新增或修改提供商
     */
    @PostMapping("save")
    public SfcJsonResult<?> save(@RequestBody LlmProvider provider) {
        if (provider.getId() != null) {
            LlmProvider existing = llmProviderService.findById(provider.getId());
            if (existing != null) {
                SfcUIDValidator.validateWithException(existing.getUid(), true);
            }
        }
        if (provider.getUid() == null) {
            provider.setUid(SecureUtils.getCurrentUid());
        }
        llmProviderService.save(provider);
        return SfcJsonResult.getInstance(provider);
    }

    /**
     * 删除提供商
     */
    @PostMapping("delete")
    public SfcJsonResult<?> delete(@RequestParam Long id) {
        LlmProvider provider = llmProviderService.findById(id);
        if (provider != null) {
            SfcUIDValidator.validateWithException(provider.getUid(), true);
            llmProviderService.delete(id);
        }
        return SfcJsonResult.emptySuccess();
    }
}
