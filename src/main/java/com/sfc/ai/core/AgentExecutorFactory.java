package com.sfc.ai.core;

import com.sfc.ai.core.adapter.LlmChatAdapterRegistry;
import com.sfc.ai.core.channel.MessageChannel;
import com.sfc.ai.service.AiConversationService;
import com.sfc.ai.service.LlmModelService;
import com.sfc.ai.service.LlmProviderService;
import com.sfc.ai.tool.CommonTools;
import com.sfc.ai.tool.NetDiskTools;
import com.sfc.ai.tool.TextSearchTools;

/**
 * {@link AgentExecutor} 工厂，将 AgentExecutor 的依赖注入与创建逻辑封装在一起。
 * <p>
 * 业务方只需传入 {@link MessageChannel} 即可创建 AgentExecutor，无需感知其内部依赖。
 */
public class AgentExecutorFactory {

    private final LlmModelService llmModelService;
    private final ChatClientService chatClientService;
    private final LlmProviderService llmProviderService;
    private final LlmChatAdapterRegistry adapterRegistry;
    private final AiConversationService aiConversationService;
    private final CommonTools commonTools;
    private final NetDiskTools netDiskTools;
    private final TextSearchTools textSearchTools;

    public AgentExecutorFactory(LlmModelService llmModelService,
                                 ChatClientService chatClientService,
                                 LlmProviderService llmProviderService,
                                 LlmChatAdapterRegistry adapterRegistry,
                                 AiConversationService aiConversationService,
                                 CommonTools commonTools,
                                 NetDiskTools netDiskTools,
                                 TextSearchTools textSearchTools) {
        this.llmModelService = llmModelService;
        this.chatClientService = chatClientService;
        this.llmProviderService = llmProviderService;
        this.adapterRegistry = adapterRegistry;
        this.aiConversationService = aiConversationService;
        this.commonTools = commonTools;
        this.netDiskTools = netDiskTools;
        this.textSearchTools = textSearchTools;
    }

    /**
     * 使用给定的消息通道创建一个新的 AgentExecutor。
     *
     * @param channel 消息通道
     * @return 新创建的 AgentExecutor 实例
     */
    public AgentExecutor create(MessageChannel channel) {
        return new AgentExecutor(
                channel,
                llmModelService,
                chatClientService,
                llmProviderService,
                adapterRegistry,
                aiConversationService,
                commonTools,
                netDiskTools,
                textSearchTools
        );
    }
}
