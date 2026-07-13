package com.sfc.ai;

import com.sfc.ai.controller.*;
import com.sfc.ai.core.adapter.DeepSeekChatAdapter;
import com.sfc.ai.core.adapter.LlmChatAdapterRegistry;
import com.sfc.ai.core.adapter.OpenAiChatAdapter;
import com.sfc.ai.config.AiWebSocketConfig;
import com.sfc.ai.model.po.LlmModel;
import com.sfc.ai.repo.LlmModelRepo;
import com.sfc.ai.core.AgentExecutorFactory;
import com.sfc.ai.core.ChatClientService;
import com.sfc.ai.service.impl.AiConversationServiceImpl;
import com.sfc.ai.service.impl.LlmModelServiceImpl;
import com.sfc.ai.service.impl.LlmProviderServiceImpl;
import com.sfc.ai.tool.CommonTools;
import com.sfc.ai.core.memory.JpaChatMemoryRepository;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@Import({
        LlmProviderController.class,
        LlmModelController.class,
        LlmProviderServiceImpl.class,
        LlmModelServiceImpl.class,
        LlmQueryController.class,
        ChatClientService.class,
        AiWebSocketConfig.class,
        AgentExecutorFactory.class,
        AiChatWebSocketHandler.class,
        JpaChatMemoryRepository.class,
        LlmChatAdapterRegistry.class,
        OpenAiChatAdapter.class,
        DeepSeekChatAdapter.class,
        LlmAdapterController.class,
        AiConversationServiceImpl.class,
        CommonTools.class
})
@EnableJpaRepositories(basePackageClasses = LlmModelRepo.class)
@EntityScan(basePackageClasses = LlmModel.class)
public class AiAutoConfiguration {

}
