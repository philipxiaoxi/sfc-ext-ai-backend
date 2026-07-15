package com.sfc.ai.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.sfc.ai.model.po.AiChatMemory;
import com.sfc.ai.model.po.AiConversation;
import com.sfc.ai.model.vo.ConversationHistoryVo;
import com.sfc.ai.model.vo.HistoryMessageVo;
import com.sfc.ai.repo.AiConversationRepo;
import com.sfc.ai.service.AiChatMemoryService;
import com.sfc.ai.service.AiConversationService;
import com.xiaotao.saltedfishcloud.exception.JsonException;
import com.xiaotao.saltedfishcloud.service.CrudServiceImpl;
import com.xiaotao.saltedfishcloud.utils.MapperHolder;
import com.xiaotao.saltedfishcloud.utils.StringUtils;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 对话记录服务实现。
 */
@Service
public class AiConversationServiceImpl extends CrudServiceImpl<AiConversation, AiConversationRepo> implements AiConversationService {

    @Autowired
    private AiChatMemoryService aiChatMemoryService;

    @Autowired
    private AiConversationRepo aiConversationRepo;

    @Override
    public List<AiConversation> findByUidOrderByUpdateAtDesc(Long uid) {
        return repository.findByUidOrderByUpdateAtDesc(uid);
    }

    @Override
    public boolean existsByConversationId(String conversationId) {
        return repository.existsByConversationId(conversationId);
    }

    @Override
    public AiConversation findByConversationId(String conversationId) {
        return aiConversationRepo.findByConversationId(conversationId).orElse(null);
    }

    @Override
    public ConversationHistoryVo getConversationMessages(String conversationId) {
        AiConversation conversation = aiConversationRepo.findByConversationId(conversationId)
                .orElseThrow(() -> new JsonException("会话不存在"));

        List<AiChatMemory> memories = aiChatMemoryService.findByConversationIdOrderById(conversationId);

        ConversationHistoryVo vo = new ConversationHistoryVo();
        vo.setConversationId(conversationId);
        vo.setTitle(conversation.getTitle());
        vo.setMessages(flattenMessages(memories));
        return vo;
    }

    /**
     * 将持久化的聊天记忆实体列表扁平化为前端可渲染的消息列表。
     * <p>
     * 处理以下消息类型：
     * <ul>
     *   <li>{@link MessageType#USER} — 直接转为用户消息</li>
     *   <li>{@link MessageType#ASSISTANT} — 提取工具调用条目、AI 文本/思考内容</li>
     *   <li>{@link MessageType#TOOL} — 匹配并更新对应工具条目的执行结果</li>
     * </ul>
     *
     * @param memories 按 ID 升序排列的聊天记忆实体列表
     * @return 扁平化的历史消息 VO 列表
     */
    private List<HistoryMessageVo> flattenMessages(List<AiChatMemory> memories) {
        List<HistoryMessageVo> result = new ArrayList<>();
        Map<String, HistoryMessageVo> pendingTools = new HashMap<>();

        for (AiChatMemory mem : memories) {
            switch (mem.getMessageType()) {
                case USER ->
                    result.add(HistoryMessageVo.user(mem.getContent()));

                case ASSISTANT -> {
                    // 1) 先输出 AI 正文与思考内容
                    if (StringUtils.hasText(mem.getContent()) || StringUtils.hasText(mem.getReasoningContent())) {
                        result.add(HistoryMessageVo.ai(mem.getContent(), mem.getReasoningContent()));
                    }

                    // 2) 再输出工具调用条目
                    if (StringUtils.hasText(mem.getToolCallData())) {
                        List<AssistantMessage.ToolCall> toolCalls = parseToolCallData(mem.getToolCallData());
                        if (toolCalls != null) {
                            for (AssistantMessage.ToolCall tc : toolCalls) {
                                HistoryMessageVo entry = HistoryMessageVo.tool(
                                        tc.id(), tc.name(), tc.arguments(), "pending");
                                pendingTools.put(tc.id(), entry);
                                result.add(entry);
                            }
                        }
                    }
                }

                case TOOL -> {
                    // 匹配并更新对应工具条目的结果
                    if (StringUtils.hasText(mem.getToolCallData())) {
                        List<ToolResponseMessage.ToolResponse> responses = parseToolResponseData(mem.getToolCallData());
                        if (responses != null) {
                            for (ToolResponseMessage.ToolResponse resp : responses) {
                                HistoryMessageVo pending = pendingTools.get(resp.id());
                                if (pending != null) {
                                    pending.setResult(toJson(resp.responseData()));
                                    pending.setStatus("done");
                                }
                            }
                        }
                    }
                }
            }
        }

        return result;
    }

    private List<AssistantMessage.ToolCall> parseToolCallData(String json) {
        try {
            return MapperHolder.mapper.readValue(json, new TypeReference<List<AssistantMessage.ToolCall>>() {});
        } catch (Exception e) {
            return null;
        }
    }

    private List<ToolResponseMessage.ToolResponse> parseToolResponseData(String json) {
        try {
            return MapperHolder.mapper.readValue(json, new TypeReference<List<ToolResponseMessage.ToolResponse>>() {});
        } catch (Exception e) {
            return null;
        }
    }

    private String toJson(Object obj) {
        try {
            return MapperHolder.mapper.writeValueAsString(obj);
        } catch (Exception e) {
            return null;
        }
    }
}
