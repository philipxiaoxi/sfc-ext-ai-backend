package com.sfc.ai.repo;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.sfc.ai.model.chat.message.ReasoningAssistantMessage;
import com.sfc.ai.model.chat.message.ReasoningContentSupport;
import com.sfc.ai.model.po.AiChatMemory;
import com.xiaotao.saltedfishcloud.utils.MapperHolder;
import org.jspecify.annotations.NonNull;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.deepseek.DeepSeekAssistantMessage;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * 基于 JPA 的聊天记忆存储实现。
 * <p>
 * 将聊天消息持久化到数据库，替代默认的 {@link org.springframework.ai.chat.memory.InMemoryChatMemoryRepository}。
 * 支持 USER、ASSISTANT、SYSTEM、TOOL 四种消息类型的存储与重建。
 */
public class JpaChatMemoryRepository implements ChatMemoryRepository {

    private final AiChatMemoryRepo aiChatMemoryRepo;

    public JpaChatMemoryRepository(AiChatMemoryRepo aiChatMemoryRepo) {
        this.aiChatMemoryRepo = aiChatMemoryRepo;
    }

    @Override
    public List<String> findConversationIds() {
        return aiChatMemoryRepo.findDistinctConversationIds();
    }

    @Override
    public List<Message> findByConversationId(String conversationId) {
        return aiChatMemoryRepo.findByConversationIdOrderById(conversationId)
                .stream()
                .map(this::toMessage)
                .toList();
    }

    @Override
    @Transactional
    public void saveAll(@NonNull String conversationId, List<Message> messages) {
        aiChatMemoryRepo.deleteByConversationId(conversationId);
        List<AiChatMemory> entities = messages.stream()
                .map(msg -> toEntity(conversationId, msg))
                .toList();
        aiChatMemoryRepo.saveAll(entities);
    }

    @Override
    @Transactional
    public void deleteByConversationId(@NonNull String conversationId) {
        aiChatMemoryRepo.deleteByConversationId(conversationId);
    }

    /**
     * 将实体转换为 Spring AI 的 Message 对象。
     *
     * @param entity 聊天记忆实体
     * @return 消息对象
     */
    private Message toMessage(AiChatMemory entity) {
        Map<String, Object> metadata = parseMetadata(entity.getMessageMetadata());
        return switch (entity.getMessageType()) {
            case USER -> UserMessage.builder()
                    .text(entity.getContent())
                    .metadata(metadata)
                    .build();
            case ASSISTANT -> {
                List<AssistantMessage.ToolCall> toolCalls = parseList(entity.getToolCallData(),
                        new TypeReference<>() {});
                yield new ReasoningAssistantMessage(
                        entity.getContent(),
                        entity.getReasoningContent(),
                        metadata,
                        toolCalls,
                        List.of()
                );
            }
            case SYSTEM -> SystemMessage.builder()
                    .text(entity.getContent())
                    .metadata(metadata)
                    .build();
            case TOOL -> {
                List<ToolResponseMessage.ToolResponse> responses = parseList(entity.getToolCallData(),
                        new TypeReference<>() {});
                yield ToolResponseMessage.builder()
                        .responses(responses)
                        .metadata(metadata)
                        .build();
            }
        };
    }

    /**
     * 将 Spring AI 的 Message 对象转换为实体。
     *
     * @param conversationId 会话 ID
     * @param message        消息对象
     * @return 聊天记忆实体
     */
    private AiChatMemory toEntity(String conversationId, Message message) {
        AiChatMemory entity = new AiChatMemory();
        entity.setConversationId(conversationId);
        entity.setMessageType(message.getMessageType());
        entity.setContent(message.getText());
        entity.setMessageMetadata(toJson(message.getMetadata()));
        if (message instanceof ReasoningContentSupport rcs) {
            entity.setReasoningContent(rcs.getReasoningContent());
        }

        if (message instanceof AssistantMessage am) {
            entity.setToolCallData(toJson(am.getToolCalls()));
        } else if (message instanceof ToolResponseMessage trm) {
            entity.setToolCallData(toJson(trm.getResponses()));
        }

        return entity;
    }

    private String toJson(Object obj) {
        try {
            return MapperHolder.mapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("序列化消息数据失败", e);
        }
    }

    /**
     * 从 JSON 字符串解析元数据 Map，null 或空串时返回空 Map。
     */
    private Map<String, Object> parseMetadata(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return MapperHolder.mapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (JsonProcessingException e) {
            throw new RuntimeException("反序列化消息元数据失败", e);
        }
    }

    /**
     * 从 JSON 字符串解析列表，null 或空串时返回空 List。
     */
    private <T> List<T> parseList(String json, TypeReference<List<T>> typeRef) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return MapperHolder.mapper.readValue(json, typeRef);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("反序列化列表数据失败", e);
        }
    }
}
