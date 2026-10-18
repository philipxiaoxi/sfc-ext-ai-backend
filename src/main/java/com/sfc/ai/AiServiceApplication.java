package com.sfc.ai;

import com.sfc.ai.model.po.LlmModel;
import com.sfc.ai.repo.LlmModelRepo;
import com.sfc.cloud.auth.annotation.EnableSfcInternalAuth;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * AI 助手微服务启动类
 */
@SpringBootApplication
@EnableDiscoveryClient
@EnableSfcInternalAuth
@EnableJpaRepositories(basePackageClasses = LlmModelRepo.class)
@EntityScan(basePackageClasses = LlmModel.class)
public class AiServiceApplication {

    /**
     * 应用入口。
     *
     * @param args 命令行参数
     */
    static void main(String[] args) {
        SpringApplication.run(AiServiceApplication.class, args);
    }
}
