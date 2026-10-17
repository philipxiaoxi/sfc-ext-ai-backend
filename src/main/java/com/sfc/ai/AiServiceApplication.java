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
 * AI 助手微服务启动类。
 *
 * <p>本服务原为宿主插件 {@code sfc-ext/sfc-ext-ai}，以 plugin-lib 形态被 {@code sfc-core}
 * Merge 加载、复用宿主的 Spring 容器与数据源。Phase 2 起它是独立进程：</p>
 * <ul>
 *   <li>继承 {@code microservices} 聚合器（Spring Boot 4.0.8 基线），<b>不继承宿主根 pom</b>；</li>
 *   <li>独立的 SQLite 数据库（{@code ./ai.db}）与独立的 JPA 持久化单元；</li>
 *   <li>身份来自网关注入的 {@code X-Sfc-Uid} 头（见 {@code application.yml} 说明）；</li>
 *   <li>网盘操作经 {@code sfc-remote-client}（Feign）回调 {@code sfc-core} 的内部接口，
 *       不再直接依赖宿主的 {@code DiskFileSystem} 等实现类。</li>
 * </ul>
 *
 * <p>{@code sfc-remote-client} 自带 Boot 自动配置（{@code SfcRemoteClientAutoConfiguration}），
 * 其中已 {@code @EnableFeignClients(SfcCoreFileFeignClient.class)}，因此本类<b>不需要</b>
 * 再声明 {@code @EnableFeignClients}——只要依赖在类路径上就能直接注入 {@code SfcCoreFileClient}。</p>
 */
@SpringBootApplication
@EnableDiscoveryClient
@EnableSfcInternalAuth
//@Import(AiAutoConfiguration.class)
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
