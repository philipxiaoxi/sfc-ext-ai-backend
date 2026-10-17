package com.sfc.ai;

import com.sfc.ai.config.AiWebSocketConfig;
import com.sfc.ai.controller.*;
import com.sfc.ai.core.AgentExecutorFactory;
import com.sfc.ai.core.ChatClientService;
import com.sfc.ai.core.ChatRunner;
import com.sfc.ai.core.ConversationTitleGenerator;
import com.sfc.ai.core.LlmResolver;
import com.sfc.ai.core.adapter.DeepSeekChatAdapter;
import com.sfc.ai.core.adapter.LlmChatAdapterRegistry;
import com.sfc.ai.core.adapter.OpenAiChatAdapter;
import com.sfc.ai.core.memory.ChatMemoryRepairer;
import com.sfc.ai.core.memory.JpaChatMemoryRepository;
import com.sfc.ai.model.po.LlmModel;
import com.sfc.ai.repo.LlmModelRepo;
import com.sfc.ai.service.impl.AiChatMemoryServiceImpl;
import com.sfc.ai.service.impl.AiConversationServiceImpl;
import com.sfc.ai.service.impl.LlmModelServiceImpl;
import com.sfc.ai.service.impl.LlmProviderServiceImpl;
import com.sfc.ai.service.impl.SensitiveToolCallRecordServiceImpl;
import com.sfc.ai.core.tool.SensitiveToolRegistry;
import com.sfc.ai.core.tool.ToolProvider;
import com.sfc.ai.tool.CommonTools;
import com.sfc.ai.tool.NetDiskTools;
import com.sfc.ai.tool.TextSearchTools;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * AI 服务的装配中枢。
 *
 * <p><b>为什么保留 {@code @Import} 这种显式清单形式：</b>被导入的这 28 个类里，大部分
 * （Service 实现、核心组件、Tool）并没有 {@code @Service}/{@code @Component} 等构造型注解，
 * 插件期是靠这里的显式 {@code @Import} 装配的。迁移为微服务后<b>刻意不改写为组件扫描</b>，
 * 以保证 Bean 集合与插件期逐字一致、不产生装配漂移。</p>
 *
 * <p>入口：{@link AiServiceApplication} 通过 {@code @Import(AiAutoConfiguration.class)} 引入本类。
 * 本类不再是 Boot 自动配置（原先靠 {@code META-INF/spring/...AutoConfiguration.imports} 注册，
 * 该文件已随「去插件化」删除）。</p>
 */
@Configuration
@Import({
        LlmProviderController.class,
        LlmModelController.class,
        LlmProviderServiceImpl.class,
        LlmModelServiceImpl.class,
        LlmQueryController.class,
        ChatClientService.class,
        AiWebSocketConfig.class,
        AiChatMemoryServiceImpl.class,
        AgentExecutorFactory.class,
        AiChatWebSocketHandler.class,
        JpaChatMemoryRepository.class,
        LlmChatAdapterRegistry.class,
        OpenAiChatAdapter.class,
        DeepSeekChatAdapter.class,
        LlmAdapterController.class,
        AiConversationController.class,
        AiConversationServiceImpl.class,
        CommonTools.class,
        NetDiskTools.class,
        TextSearchTools.class,
        ConversationTitleGenerator.class,
        ChatRunner.class,
        LlmResolver.class,
        ChatMemoryRepairer.class,
        ToolProvider.class,
        SensitiveToolRegistry.class,
        SensitiveToolCallRecordServiceImpl.class,
        SensitiveToolCallRecordController.class
})
public class AiAutoConfiguration {

}
