package com.sfc.ai.controller;

import com.sfc.ai.model.po.LlmModel;
import com.sfc.ai.service.LlmModelService;
import com.xiaotao.saltedfishcloud.constant.error.CommonError;
import com.xiaotao.saltedfishcloud.exception.JsonException;
import com.xiaotao.saltedfishcloud.model.json.JsonResult;
import com.xiaotao.saltedfishcloud.model.json.JsonResultImpl;
import com.xiaotao.saltedfishcloud.utils.SecureUtils;
import com.xiaotao.saltedfishcloud.validator.UIDValidator;
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
    public JsonResult<?> list(@RequestParam(required = false) Long providerId) {
        Long uid = SecureUtils.getCurrentUid();
        if (providerId != null) {
            return JsonResultImpl.getInstance(llmModelService.findByProviderId(providerId));
        }
        return JsonResultImpl.getInstance(llmModelService.findByUid(uid));
    }

    /**
     * 根据 ID 查询模型
     */
    @GetMapping("get")
    public JsonResult<?> get(@RequestParam Long id) {
        LlmModel model = llmModelService.findById(id);
        if (model == null) {
            throw new JsonException(CommonError.RESOURCE_NOT_FOUND);
        }
        if (!UIDValidator.validate(model.getUid(), true)) {
            throw new JsonException(CommonError.SYSTEM_FORBIDDEN);
        }
        return JsonResultImpl.getInstance(model);
    }

    /**
     * 新增或修改模型
     */
    @PostMapping("save")
    public JsonResult<?> save(@RequestBody LlmModel model) {
        if (model.getId() != null) {
            LlmModel existing = llmModelService.findById(model.getId());
            if (existing != null) {
                UIDValidator.validateWithException(existing.getUid(), true);
            }
        }
        if (model.getUid() == null) {
            model.setUid(SecureUtils.getCurrentUid());
        }
        llmModelService.save(model);
        return JsonResultImpl.getInstance(model);
    }

    /**
     * 删除模型
     */
    @PostMapping("delete")
    public JsonResult<?> delete(@RequestParam Long id) {
        LlmModel model = llmModelService.findById(id);
        if (model != null) {
            UIDValidator.validateWithException(model.getUid(), true);
            llmModelService.delete(id);
        }
        return JsonResult.emptySuccess();
    }

    /**
     * 根据提供商 ID 删除所有关联模型
     */
    @PostMapping("deleteByProviderId")
    public JsonResult<?> deleteByProviderId(@RequestParam Long providerId) {
        llmModelService.deleteByProviderId(providerId);
        return JsonResult.emptySuccess();
    }
}
