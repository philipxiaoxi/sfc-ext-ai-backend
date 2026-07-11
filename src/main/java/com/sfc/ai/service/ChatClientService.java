package com.sfc.ai.service;

import com.sfc.ai.model.po.LlmModel;
import com.sfc.ai.model.po.LlmProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;

import java.util.function.Consumer;

/**
 * AI 聊天客户端服务，用于根据 LLM 提供商和模型配置构建 {@link ChatClient} 实例。
 */
@RequiredArgsConstructor
public class ChatClientService {

    private final ChatMemoryRepository chatMemoryRepository;

    /**
     * 获取一个配置好的 ChatClient 实例。
     *
     * @param llmProvider     LLM 提供商配置（含 API Key、地址等）
     * @param model           使用的模型配置（含模型 ID）
     * @param builderConsumer 可选的 ChatClient.Builder 定制回调，用于进一步自定义客户端构造
     * @return 配置完成的 ChatClient 实例
     * @throws IllegalArgumentException 如果提供商协议类型不是 OpenAI
     */
    public ChatClient getChatClient(LlmProvider llmProvider, LlmModel model, Consumer<ChatClient.Builder> builderConsumer) {
        if (llmProvider.getProtocolType() != LlmProvider.ProtocolType.OpenAI) {
            throw new IllegalArgumentException("不支持的协议类型");
        }

        OpenAiChatModel openAiChatModel = OpenAiChatModel.builder()
                .options(OpenAiChatOptions.builder()
                        .model(model.getModelId())
                        .apiKey(llmProvider.getApiKey())
                        .baseUrl(llmProvider.getBaseUrl())
                        .reasoningEffort(model.getReasoningEffect())
                        .build())
                .build();
        ChatClient.Builder builder = ChatClient.builder(openAiChatModel);
        ChatMemory chatMemory = MessageWindowChatMemory.builder().chatMemoryRepository(chatMemoryRepository)
                .build();
        builder.defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build());
        if (builderConsumer != null) {
            builderConsumer.accept(builder);
        }
        return builder.build();
    }

    /**
     * 获取一个配置好的 ChatClient 实例（不带 Builder 定制回调）。
     *
     * @param llmProvider LLM 提供商配置
     * @param model       使用的模型配置
     * @param conversationId 会话 ID，用于在 ChatMemory 中区分不同的对话
     * @return 配置完成的 ChatClient 实例
     */
    public ChatClient getChatClient(LlmProvider llmProvider, LlmModel model, String conversationId) {
        return getChatClient(
                llmProvider,
                model,
                builder -> builder.defaultAdvisors(advisorSpec -> advisorSpec.param(ChatMemory.CONVERSATION_ID, conversationId))
        );
    }
}
