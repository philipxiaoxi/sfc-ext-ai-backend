package com.sfc.ai.core;

import com.sfc.ai.core.adapter.LlmChatAdapter;
import com.sfc.ai.core.adapter.LlmChatAdapterRegistry;
import com.sfc.ai.core.advisor.MessageConvertAdvisor;
import com.sfc.ai.core.advisor.SfcChatMemoryAdvisor;
import com.sfc.ai.core.memory.SfcChatMemory;
import com.sfc.ai.core.tool.FallbackToolCallbackResolver;
import com.sfc.ai.model.po.LlmModel;
import com.sfc.ai.model.po.LlmProvider;
import com.sfc.ai.tool.CommonTools;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.tool.ToolCallingManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

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
     * 获取一个配置好的 ChatClient 实例，允许外部通过 {@link Consumer} 自定义 Builder
     * （如注册工具、添加 Advisors 等）。
     *
     * @param llmProvider      LLM 提供商配置（含 API Key、地址、适配器标识等）
     * @param model            使用的模型配置（含模型 ID）
     * @param conversationId   会话 ID，用于在 ChatMemory 中区分不同的对话
     * @param adapter          聊天适配器，用于消息转换和记忆
     * @param builderConsumer  Builder 自定义回调，可为 null；在此回调中可调用
     *                         {@link ChatClient.Builder#defaultTools(Object...)} 等注册工具
     * @return 配置完成的 ChatClient 实例
     */
    public ChatClient getChatClient(LlmProvider llmProvider, LlmModel model, String conversationId,
                                      LlmChatAdapter adapter,
                                      Consumer<ChatClient.Builder> builderConsumer) {
        ChatModel chatModel = adapterRegistry.getAdapter(llmProvider.getAdapter())
                .createChatModel(llmProvider, model);

        ToolCallingAdvisor toolCallingAdvisor = ToolCallingAdvisor.builder()
                .toolCallingManager(ToolCallingManager.builder()
                        .toolCallbackResolver(new FallbackToolCallbackResolver())
                        .build())
                .conversationHistoryEnabled(false)
                .build();

        ChatClient.Builder builder = ChatClient.builder(chatModel)
                .defaultAdvisors(
                        toolCallingAdvisor,
                        new SfcChatMemoryAdvisor(adapter, SfcChatMemory.builder()
                                .chatMemoryRepository(chatMemoryRepository)
                                .build()),
                        new MessageConvertAdvisor(adapter));
        builder.defaultAdvisors(advisorSpec -> advisorSpec.param(ChatMemory.CONVERSATION_ID, conversationId));

        if (builderConsumer != null) {
            builderConsumer.accept(builder);
        }

        return builder.build();
    }

    /**
     * 获取一个配置好的 ChatClient 实例（内部已合并内建工具与额外工具）。
     *
     * @param llmProvider    LLM 提供商配置（含 API Key、地址、适配器标识等）
     * @param model          使用的模型配置（含模型 ID）
     * @param conversationId 会话 ID，用于在 ChatMemory 中区分不同的对话
     * @param extraTools     额外注入的工具回调（如动态注册的工具），与 commonTools 合并
     * @return 配置完成的 ChatClient 实例
     */
    public ChatClient getChatClient(LlmProvider llmProvider, LlmModel model, String conversationId,
                                     LlmChatAdapter adapter, Object... extraTools) {
        // 合并内建工具与额外工具
        List<Object> allTools = new ArrayList<>();
        allTools.add(commonTools);
        Collections.addAll(allTools, extraTools);

        return getChatClient(llmProvider, model, conversationId, adapter,
                builder -> builder.defaultTools(allTools.toArray()));
    }


}
