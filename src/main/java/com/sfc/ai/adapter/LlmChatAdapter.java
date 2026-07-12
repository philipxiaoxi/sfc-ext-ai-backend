package com.sfc.ai.adapter;

import com.sfc.ai.model.po.LlmModel;
import com.sfc.ai.model.po.LlmProvider;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;

import java.util.List;

/**
 * LLM 聊天模型适配器接口。
 * <p>
 * 每种大模型提供商（OpenAI、DeepSeek 等）实现此接口，用于根据 {@link LlmProvider} 和
 * {@link LlmModel} 的配置创建对应的 Spring AI {@link ChatModel} 实例。
 * <p>
 * 实现类需注册为 Spring Bean，将由 {@link LlmChatAdapterRegistry} 自动发现。
 */
public interface LlmChatAdapter {

    /**
     * 获取适配器唯一标识，如 "openai"、"deepseek"
     *
     * @return 适配器 ID
     */
    String getId();

    /**
     * 获取适配器显示名称，如 "OpenAI"、"DeepSeek"
     *
     * @return 显示名称
     */
    String getName();

    /**
     * 获取适配器图标标识。
     * <p>
     * 可以是 Material Design Icon 名称、HTTP/HTTPS 图片 URL 或 base64 图片 Data URL。
     *
     * @return 图标标识，可为 null
     */
    default String getIcon() {
        return "mdi-robot-outline";
    }

    /**
     * 根据提供商配置和模型配置创建 Spring AI {@link ChatModel} 实例。
     *
     * @param provider 提供商配置（含 API Key、请求地址等）
     * @param model    模型配置（含模型 ID、推理效果等）
     * @return ChatModel 实例
     */
    ChatModel createChatModel(LlmProvider provider, LlmModel model);

    /**
     * 从模型响应中提取推理内容（Chain of Thought）。
     * <p>
     * 各适配器根据自身模型响应的具体类型提取 CoT 内容。
     * 默认实现返回 null，表示不支持推理内容提取。
     *
     * @param message 模型返回的响应消息
     * @return 推理内容文本，如果不支持或不存在则返回 null
     */
    @Nullable
    default String extractReasoningContent(AssistantMessage message) {
        return null;
    }

    /**
     * 预处理即将发送给 LLM 的消息列表。
     * <p>
     * 适配器可将通用消息类型转换为提供商特定的消息类型，
     * 以保留厂商特有的消息字段（如推理内容），确保序列化为 HTTP 请求时不丢失。
     * 默认实现直接返回原列表，无需转换的适配器无需重写。
     *
     * @param messages 当前请求的消息列表（含从记忆恢复的历史消息）
     * @return 预处理后的消息列表
     */
    default List<Message> preprocessMessages(List<Message> messages) {
        return messages;
    }
}
