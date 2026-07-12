package com.sfc.ai.service;

import com.sfc.ai.adapter.LlmChatAdapter;
import com.sfc.ai.adapter.LlmChatAdapterRegistry;
import com.sfc.ai.advisor.MessageConvertAdvisor;
import com.sfc.ai.advisor.SfcChatMemoryAdvisor;
import com.sfc.ai.core.SfcChatMemory;
import com.sfc.ai.model.po.LlmModel;
import com.sfc.ai.model.po.LlmProvider;
import com.sfc.ai.tool.CommonTools;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.model.ChatModel;

/**
 * AI 聊天客户端服务，用于根据 LLM 提供商和模型配置构建 {@link ChatClient} 实例。
 */
public class ChatClientService {

    private final ChatMemoryRepository chatMemoryRepository;

    private final LlmChatAdapterRegistry adapterRegistry;

    private final CommonTools commonTools;

    public ChatClientService(ChatMemoryRepository chatMemoryRepository,
                              LlmChatAdapterRegistry adapterRegistry,
                              CommonTools commonTools) {
        this.chatMemoryRepository = chatMemoryRepository;
        this.adapterRegistry = adapterRegistry;
        this.commonTools = commonTools;
    }

    /**
     * 获取一个配置好的 ChatClient 实例。
     *
     * @param llmProvider    LLM 提供商配置（含 API Key、地址、适配器标识等）
     * @param model          使用的模型配置（含模型 ID）
     * @param conversationId 会话 ID，用于在 ChatMemory 中区分不同的对话
     * @return 配置完成的 ChatClient 实例
     */
    public ChatClient getChatClient(LlmProvider llmProvider, LlmModel model, String conversationId, LlmChatAdapter adapter) {
        // 根据配置获取对话模型
        ChatModel chatModel = adapterRegistry.getAdapter(llmProvider.getAdapter())
                .createChatModel(llmProvider, model);

        // 配置记忆模块与消息转换
        ChatClient.Builder builder = ChatClient.builder(chatModel)
                .defaultAdvisors(
                        new SfcChatMemoryAdvisor(adapter, SfcChatMemory.builder()
                                .chatMemoryRepository(chatMemoryRepository)
                                .build()),
                        new MessageConvertAdvisor(adapter))
                .defaultTools(commonTools);
        builder.defaultAdvisors(advisorSpec -> advisorSpec.param(ChatMemory.CONVERSATION_ID, conversationId));

        // 组合构建对话客户端
        return builder.build();
    }
}
