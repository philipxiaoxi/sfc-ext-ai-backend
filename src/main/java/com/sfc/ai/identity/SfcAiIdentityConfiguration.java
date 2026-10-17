package com.sfc.ai.identity;

import com.sfc.cloud.data.identity.CurrentUserHolder;
import com.sfc.cloud.data.identity.CurrentUserProvider;
import com.sfc.cloud.remote.client.SfcCoreUserClient;
import com.sfc.cloud.trust.SfcTrustHeaders;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * sfc-ai-service 的身份装配。
 *
 * <p><b>装配方式：组件扫描</b>。本类位于 {@code com.sfc.ai.identity}，落在启动类
 * {@code com.sfc.ai.AiServiceApplication} 的 {@code @SpringBootApplication} 扫描范围
 * （{@code com.sfc.ai.**}）之内，因此无需修改 {@code AiAutoConfiguration} 的 {@code @Import} 清单
 * （该清单的语义是「与插件期逐字一致、零装配漂移」，身份设施是本服务新增能力，不应混入）。</p>
 *
 * <p><b>做了三件事：</b></p>
 * <ol>
 *   <li>声明用户档案回源解析器（{@code SfcCoreUserClient} 缺失时自动降级）；</li>
 *   <li>声明 {@link CurrentUserProvider} 的微服务实现（委托 {@link SfcCurrentUserSupport}）；</li>
 *   <li>在启动期把两者注册进静态持有者：{@link SfcCurrentUserSupport#setProfileResolver} 与
 *       {@link CurrentUserHolder#setProvider}。</li>
 * </ol>
 *
 * <p><b>为什么用 {@link InitializingBean} 而不是 {@code @PostConstruct}：</b>与宿主侧
 * {@code SfcSharedBaseBridgeConfiguration} 保持同一手法——以 Bean 形式注册可避免在同一个
 * {@code @Configuration} 内构造器自注入造成的循环引用，同时晚于被注册对象自身初始化，
 * 顺序明确；且 {@code InitializingBean} Bean 在容器 refresh 的单例实例化阶段完成回调，
 * 早于 Web 容器开始接收请求，因此第一个请求到达时身份必然已就绪。</p>
 *
 * <p><b>fail-closed 语义：</b>若本配置未生效，共享基座的权限判定一律不通过（uid 为 null、
 * admin 为 false），本模块的 uid 校验会全部拒绝——这是刻意设计，避免「静默放行」。</p>
 *
 * @see SfcCurrentUserSupport
 * @see SfcCurrentUserProvider
 */
@Slf4j
@Configuration
public class SfcAiIdentityConfiguration {

    /**
     * 用户档案回源解析器：以信任头 uid 换取 username / admin。
     *
     * <p>远程用户客户端以 {@link ObjectProvider} 形式注入（可选依赖）：
     * {@code sfc-remote-client} 的自动装配提供它，取不到时解析器自动降级，不影响本服务启动。</p>
     *
     * @param userClientProvider 远程用户客户端的可选提供者
     * @return 用户档案解析器
     */
    @Bean
    public SfcUserProfileResolver sfcUserProfileResolver(ObjectProvider<SfcCoreUserClient> userClientProvider) {
        return new SfcCoreUserProfileResolver(userClientProvider);
    }

    /**
     * 微服务侧的身份提供者（{@link CurrentUserProvider} SPI 实现）。
     *
     * @return 身份提供者
     */
    @Bean
    public CurrentUserProvider sfcCurrentUserProvider() {
        return new SfcCurrentUserProvider();
    }

    /**
     * 把身份相关实现注册进共享基座与身份支持的静态持有者。
     *
     * @param currentUserProvider    身份提供者
     * @param sfcUserProfileResolver 用户档案回源解析器
     * @return 完成注册的初始化回调
     */
    @Bean
    public InitializingBean sfcIdentityHolderBinder(CurrentUserProvider currentUserProvider,
                                                    SfcUserProfileResolver sfcUserProfileResolver) {
        return () -> {
            SfcCurrentUserSupport.setProfileResolver(sfcUserProfileResolver);
            CurrentUserHolder.setProvider(currentUserProvider);
            log.info("[身份] sfc-ai-service 身份装配完成：uid 取自网关注入的 {} 信任头", SfcTrustHeaders.UID);
        };
    }
}
