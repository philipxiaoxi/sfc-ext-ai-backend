package com.sfc.ai.controller;

import com.sfc.ai.model.po.LlmProvider;
import com.sfc.ai.service.LlmProviderService;
import com.xiaotao.saltedfishcloud.constant.error.CommonError;
import com.xiaotao.saltedfishcloud.exception.JsonException;
import com.xiaotao.saltedfishcloud.model.json.JsonResult;
import com.xiaotao.saltedfishcloud.model.json.JsonResultImpl;
import com.xiaotao.saltedfishcloud.utils.SecureUtils;
import com.xiaotao.saltedfishcloud.validator.UIDValidator;
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
    public JsonResult<?> list() {
        Long uid = SecureUtils.getCurrentUid();
        return JsonResultImpl.getInstance(llmProviderService.findByUid(uid));
    }

    /**
     * 根据 ID 查询提供商
     */
    @GetMapping("get")
    public JsonResult<?> get(@RequestParam Long id) {
        LlmProvider provider = llmProviderService.findById(id);
        if (provider == null) {
            throw new JsonException(CommonError.RESOURCE_NOT_FOUND);
        }
        if (!UIDValidator.validate(provider.getUid(), true)) {
            throw new JsonException(CommonError.SYSTEM_FORBIDDEN);
        }
        return JsonResultImpl.getInstance(provider);
    }

    /**
     * 新增或修改提供商
     */
    @PostMapping("save")
    public JsonResult<?> save(@RequestBody LlmProvider provider) {
        if (provider.getId() != null) {
            LlmProvider existing = llmProviderService.findById(provider.getId());
            if (existing != null) {
                UIDValidator.validateWithException(existing.getUid(), true);
            }
        }
        if (provider.getUid() == null) {
            provider.setUid(SecureUtils.getCurrentUid());
        }
        llmProviderService.save(provider);
        return JsonResultImpl.getInstance(provider);
    }

    /**
     * 删除提供商
     */
    @PostMapping("delete")
    public JsonResult<?> delete(@RequestParam Long id) {
        LlmProvider provider = llmProviderService.findById(id);
        if (provider != null) {
            UIDValidator.validateWithException(provider.getUid(), true);
            llmProviderService.delete(id);
        }
        return JsonResult.emptySuccess();
    }
}
