package com.sfc.ai.controller;

import com.sfc.ai.model.po.AiConversation;
import com.sfc.ai.model.vo.ConversationHistoryVo;
import com.sfc.ai.service.AiConversationService;
import com.xiaotao.saltedfishcloud.exception.JsonException;
import com.xiaotao.saltedfishcloud.model.json.JsonResult;
import com.xiaotao.saltedfishcloud.model.json.JsonResultImpl;
import com.xiaotao.saltedfishcloud.validator.UIDValidator;
import com.xiaotao.saltedfishcloud.utils.SecureUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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

    /**
     * 获取指定会话的完整历史消息记录。
     * <p>
     * 返回的 {@link ConversationHistoryVo#getMessages()} 包含用户消息、AI 助手思考内容与正文、
     * 工具调用及其结果，前端可直接渲染。
     *
     * @param conversationId 会话 ID（对应 WebSocket 协议的 sessionId）
     * @return 会话历史 VO
     */
    @GetMapping("messages")
    public JsonResult<ConversationHistoryVo> getMessages(@RequestParam String conversationId) {
        AiConversation conversation = aiConversationService.findByConversationId(conversationId);
        if (conversation == null) {
            throw new JsonException("会话不存在");
        }
        UIDValidator.validateWithException(conversation.getUid(), true);
        return JsonResultImpl.getInstance(aiConversationService.getConversationMessages(conversationId));
    }
}
