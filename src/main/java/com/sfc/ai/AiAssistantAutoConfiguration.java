package com.sfc.ai;

import com.sfc.ai.controller.AiAssistantController;
import com.sfc.ai.model.po.LlmModel;
import com.sfc.ai.repo.LlmModelRepo;
import com.sfc.ai.service.impl.LlmModelServiceImpl;
import com.sfc.ai.service.impl.LlmProviderServiceImpl;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@Import({
        AiAssistantController.class,
        LlmProviderServiceImpl.class,
        LlmModelServiceImpl.class
})
@EnableJpaRepositories(basePackageClasses = LlmModelRepo.class)
@EntityScan(basePackageClasses = LlmModel.class)
public class AiAssistantAutoConfiguration {
}
