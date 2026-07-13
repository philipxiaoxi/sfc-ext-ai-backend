package com.sfc.ai.controller;

import com.sfc.ai.model.po.AiConversation;
import com.sfc.ai.service.AiConversationService;
import com.xiaotao.saltedfishcloud.model.json.JsonResult;
import com.xiaotao.saltedfishcloud.model.json.JsonResultImpl;
import com.xiaotao.saltedfishcloud.utils.SecureUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * AI 对话管理控制器。
 */
@RestController
@RequestMapping("/api/ai/conversation")
public class AiConversationController {

    @Autowired
    private AiConversationService aiConversationService;

    /**
     * 获取当前用户的对话列表，按更新时间降序排列。
     */
    @GetMapping("list")
    public JsonResult<List<AiConversation>> list() {
        Long uid = SecureUtils.getCurrentUid();
        return JsonResultImpl.getInstance(aiConversationService.findByUidOrderByUpdateAtDesc(uid));
    }
}
