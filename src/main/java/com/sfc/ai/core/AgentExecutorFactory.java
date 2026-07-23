package com.sfc.ai.core;

import com.sfc.ai.core.channel.MessageChannel;
import com.sfc.ai.core.memory.ChatMemoryRepairer;
import com.sfc.ai.service.AiConversationService;
import lombok.RequiredArgsConstructor;

/**
 * {@link AgentExecutor} 工厂。
 * <p>
 * 业务方只需传入 {@link MessageChannel} 和 {@link AgentExecutorConfig} 即可创建
 * {@link AgentExecutor}，无需感知其内部依赖。
 * <p>
 * 工厂自身为 Spring 单例，持有 {@link ChatRunner}、{@link ChatMemoryRepairer}、
 * {@link AiConversationService} 三个无状态协作 Bean。
 */
@RequiredArgsConstructor
public class AgentExecutorFactory {

    private final ChatRunner chatRunner;
    private final ChatMemoryRepairer compensator;
    private final AiConversationService aiConversationService;

    /**
     * 使用给定的消息通道和配置创建一个新的 {@link AgentExecutor}。
     *
     * @param channel 消息通道
     * @param config  Agent 执行器配置
     * @return 新创建的 {@link AgentExecutor} 实例
     */
    public AgentExecutor create(MessageChannel channel, AgentExecutorConfig config) {
        return new AgentExecutor(channel, chatRunner, compensator, aiConversationService, config);
    }
}
