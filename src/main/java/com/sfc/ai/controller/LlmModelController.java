package com.sfc.ai.controller;

import com.sfc.ai.model.po.LlmModel;
import com.sfc.ai.service.LlmModelService;
import com.sfc.cloud.constant.SfcCommonError;
import com.sfc.cloud.exception.SfcJsonException;
import com.sfc.cloud.model.json.SfcJsonResult;
import com.xiaotao.saltedfishcloud.utils.SecureUtils;
import com.sfc.cloud.data.validation.SfcUIDValidator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

/**
 * 模型管理控制器
 */
@RestController
@RequestMapping("/api/ai/model")
public class LlmModelController {

    @Autowired
    private LlmModelService llmModelService;

    /**
     * 查询当前用户的模型列表，可按提供商 ID 过滤
     */
    @GetMapping("list")
    public SfcJsonResult<?> list(@RequestParam(required = false) Long providerId) {
        Long uid = SecureUtils.getCurrentUid();
        if (providerId != null) {
            return SfcJsonResult.getInstance(llmModelService.findByProviderId(providerId));
        }
        return SfcJsonResult.getInstance(llmModelService.findByUid(uid));
    }

    /**
     * 根据 ID 查询模型
     */
    @GetMapping("get")
    public SfcJsonResult<?> get(@RequestParam Long id) {
        LlmModel model = llmModelService.findById(id);
        if (model == null) {
            throw new SfcJsonException(SfcCommonError.RESOURCE_NOT_FOUND);
        }
        if (!SfcUIDValidator.validate(model.getUid(), true)) {
            throw new SfcJsonException(SfcCommonError.SYSTEM_FORBIDDEN);
        }
        return SfcJsonResult.getInstance(model);
    }

    /**
     * 新增或修改模型
     */
    @PostMapping("save")
    public SfcJsonResult<?> save(@RequestBody LlmModel model) {
        if (model.getId() != null) {
            LlmModel existing = llmModelService.findById(model.getId());
            if (existing != null) {
                SfcUIDValidator.validateWithException(existing.getUid(), true);
            }
        }
        if (model.getUid() == null) {
            model.setUid(SecureUtils.getCurrentUid());
        }
        llmModelService.save(model);
        return SfcJsonResult.getInstance(model);
    }

    /**
     * 删除模型
     */
    @PostMapping("delete")
    public SfcJsonResult<?> delete(@RequestParam Long id) {
        LlmModel model = llmModelService.findById(id);
        if (model != null) {
            SfcUIDValidator.validateWithException(model.getUid(), true);
            llmModelService.delete(id);
        }
        return SfcJsonResult.emptySuccess();
    }

    /**
     * 根据提供商 ID 删除所有关联模型
     */
    @PostMapping("deleteByProviderId")
    public SfcJsonResult<?> deleteByProviderId(@RequestParam Long providerId) {
        llmModelService.deleteByProviderId(providerId);
        return SfcJsonResult.emptySuccess();
    }
}
