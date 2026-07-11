package com.sfc.ai.controller;

import com.sfc.ai.adapter.AdapterInfo;
import com.sfc.ai.adapter.LlmChatAdapterRegistry;
import com.xiaotao.saltedfishcloud.model.json.JsonResult;
import com.xiaotao.saltedfishcloud.model.json.JsonResultImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * LLM 适配器管理控制器，用于查询系统当前支持的 AI 模型适配器信息。
 */
@RestController
@RequestMapping("/api/ai/adapter")
public class LlmAdapterController {

    @Autowired
    private LlmChatAdapterRegistry adapterRegistry;

    /**
     * 获取系统当前支持的所有 LLM 提供商适配器列表。
     *
     * @return 适配器信息列表，每项包含 id、name、icon
     */
    @GetMapping("list")
    public JsonResult<List<AdapterInfo>> list() {
        return JsonResultImpl.getInstance(adapterRegistry.listAdapters());
    }
}
