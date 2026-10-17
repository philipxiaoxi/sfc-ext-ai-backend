package com.sfc.ai.identity;

import com.sfc.common.model.UserPrincipal;
import com.sfc.common.model.json.SfcJsonResult;
import com.sfc.cloud.remote.client.SfcCoreUserClient;
import com.sfc.common.utils.SfcStringUtils;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 回源到 sfc-core 的用户档案解析器（{@link SfcUserProfileResolver} 的默认实现）。
 *
 * <p><b>数据来源：</b>{@code sfc-remote-client} 的 {@link SfcCoreUserClient#getCurrentUser()}
 * （内部请求 core 的 {@code GET /api/user}，由 Feign 拦截器透传入站请求的用户凭证），
 * 取响应信封 {@code data} 中的 {@link UserPrincipal}：{@code user} → username
 * （共享模型以 {@code @JsonProperty("user")} 映射）、{@code admin} → 管理员标记
 * （由宿主 {@code UserPrincipal.isAdmin()} 序列化而来）。</p>
 *
 * <p><b>为什么是「可选依赖」：</b>该客户端由 {@code sfc-remote-client} 的自动装配提供，
 * 本服务不应因为它缺席而启动失败（例如裁剪依赖、或该客户端尚未随版本落地）。
 * 因此经 {@link ObjectProvider} 注入，取不到 Bean 时永久降级并打印一次提示日志。</p>
 *
 * <p><b>降级策略（任何一环失败都不抛异常）：</b></p>
 * <ul>
 *   <li>容器中没有 {@link SfcCoreUserClient} Bean → 返回 {@code null}；</li>
 *   <li>远程调用抛异常（含 core 无登录态时的 401、网络/负载均衡失败）→ 返回 {@code null}；</li>
 *   <li>响应信封 {@code code != 200}、{@code data} 为空 → 返回 {@code null}；</li>
 *   <li>回源得到的 uid 与信任头 uid <b>不一致</b> → 返回 {@code null}（防御串号与缓存污染：
 *       远程接口按「入站凭证」识别调用者，凭证与信任头必须自洽）；</li>
 *   <li>当前线程没有入站 HTTP 请求 → 直接返回 {@code null} 且不发远程调用。</li>
 * </ul>
 * <p>降级后 uid 依旧可用，username 退化为 {@code uid:<id>}、admin 恒为 {@code false}（fail-closed）。</p>
 *
 * <p><b>为什么「没有入站请求就不回源」：</b>远程客户端靠透传入站请求的用户凭证来识别「当前用户」
 * （见 {@code FeignCredentialForwardingInterceptor}：无入站请求时不携带任何用户凭证），
 * 此时 core 必然返回 401「未登录」。因此 WebSocket 消息线程 / 工具执行线程 / 定时任务线程上
 * 回源不仅拿不到本人档案，还会每次平白打一次 core，故直接短路降级。</p>
 *
 * <p><b>缓存：</b>按 uid 缓存解析结果，避免权限校验热路径（{@code SfcUIDValidator} 每次调用都要
 * 读 admin）反复发起远程调用。成功结果 TTL {@value #PROFILE_TTL_MILLIS} 毫秒；
 * 失败结果只做 {@value #FAILURE_TTL_MILLIS} 毫秒的短负缓存（用于限流，避免偶发失败被长期固化，
 * 也避免把无凭证线程的降级结果污染给并发的正常请求）。
 * 缓存实例为<b>只读共享</b>，调用方不得修改返回的 {@link UserPrincipal}。</p>
 *
 * <p><b>已知副作用（已获确认）：</b>core 的 {@code GET /api/user} 会顺带刷新用户 token 的有效期，
 * 因此本解析器每 {@value #PROFILE_TTL_MILLIS} 毫秒最多触发一次该接口调用。</p>
 *
 * @see SfcUserProfileResolver
 * @see SfcCurrentUserSupport#setProfileResolver(SfcUserProfileResolver)
 */
@Slf4j
public class SfcCoreUserProfileResolver implements SfcUserProfileResolver {

    /** 成功结果缓存时长（毫秒）：兼顾缓存收益、身份实时性与 core 侧 token 续期频率 */
    private static final long PROFILE_TTL_MILLIS = 60_000L;

    /** 失败结果短负缓存时长（毫秒）：仅用于限流，避免偶发失败被固化 */
    private static final long FAILURE_TTL_MILLIS = 5_000L;

    /** 缓存条目上限，超过后清理过期条目，避免长期运行下无界增长 */
    private static final int MAX_CACHE_ENTRIES = 1024;

    /** 降级用户名前缀：无法回源取得真实用户名时使用 */
    private static final String FALLBACK_USERNAME_PREFIX = "uid:";

    /** 远程用户客户端（可选依赖）；容器中不存在该 Bean 时为 {@code null} */
    private final ObjectProvider<SfcCoreUserClient> userClientProvider;

    /** uid → 档案缓存（同时承载成功与失败结果） */
    private final Map<Long, CacheEntry> cache = new ConcurrentHashMap<>();

    /** 是否已完成「远程客户端可用性」探测（Bean 的有无在运行期不会变化，只探测一次） */
    private volatile boolean clientProbed;

    /** 探测到的远程用户客户端；不可用时为 {@code null} */
    private volatile SfcCoreUserClient client;

    /**
     * 构造解析器。
     *
     * @param userClientProvider 远程用户客户端的可选提供者（容器中不存在该 Bean 时降级）
     */
    public SfcCoreUserProfileResolver(ObjectProvider<SfcCoreUserClient> userClientProvider) {
        this.userClientProvider = userClientProvider;
    }

    /**
     * {@inheritDoc}
     *
     * <p>本实现保证不抛异常：全部失败路径都归一为返回 {@code null}。</p>
     */
    @Override
    @Nullable
    public UserPrincipal resolve(Long uid) {
        if (uid == null) {
            return null;
        }
        if (SfcCurrentUserSupport.currentRequest() == null) {
            // 无入站请求 → 用户凭证无法透传 → 回源必然拿不到本人身份，直接降级（不产生远程调用）
            log.debug("[身份] 当前线程无入站请求上下文，跳过用户档案回源: uid={}", uid);
            return null;
        }
        SfcCoreUserClient remoteClient = resolveClient();
        if (remoteClient == null) {
            return null;
        }

        long now = System.currentTimeMillis();
        CacheEntry cached = cache.get(uid);
        if (cached != null && cached.expireAt() > now) {
            return cached.profile();
        }

        UserPrincipal profile = fetch(uid, remoteClient);
        cache.put(uid, new CacheEntry(profile, now + (profile == null ? FAILURE_TTL_MILLIS : PROFILE_TTL_MILLIS)));
        evictExpiredIfNeeded(now);
        return profile;
    }

    /**
     * 探测并返回远程用户客户端（只探测一次，结果永久缓存）。
     *
     * @return 远程用户客户端；容器中没有该 Bean 时返回 {@code null}
     */
    @Nullable
    private SfcCoreUserClient resolveClient() {
        if (clientProbed) {
            return client;
        }
        synchronized (this) {
            if (clientProbed) {
                return client;
            }
            try {
                client = userClientProvider.getIfAvailable();
            } catch (RuntimeException e) {
                // 例如 Feign 客户端因缺少负载均衡器而创建失败：一律降级，不影响本服务启动
                client = null;
                log.warn("[身份] 远程用户客户端创建失败，username/admin 将降级（uid 不受影响）: {}", e.toString());
            }
            clientProbed = true;
            if (client == null) {
                log.info("[身份] 容器中不存在 {} Bean，username/admin 将降级（uid 不受影响）",
                        SfcCoreUserClient.class.getName());
            } else {
                log.info("[身份] 已启用用户档案回源: {}#getCurrentUser", SfcCoreUserClient.class.getName());
            }
            return client;
        }
    }

    /**
     * 发起一次远程回源并解析响应。
     *
     * @param uid          期望的用户 uid
     * @param remoteClient 远程用户客户端
     * @return 用户档案；任何失败都返回 {@code null}
     */
    @Nullable
    private UserPrincipal fetch(Long uid, SfcCoreUserClient remoteClient) {
        SfcJsonResult<UserPrincipal> envelope;
        try {
            envelope = remoteClient.getCurrentUser();
        } catch (Exception e) {
            // core 无登录态时返回 401，网络/负载均衡失败也会走到这里；一律降级
            log.debug("[身份] 用户档案回源调用失败，降级为 uid-only 身份: uid={}, cause={}", uid, e.toString());
            return null;
        }
        return extractProfile(envelope, uid);
    }

    /**
     * 从远程响应信封中提取用户档案。
     *
     * @param envelope 远程响应信封；可为 {@code null}
     * @param uid      期望的用户 uid
     * @return 用户档案；信封为空 / 业务失败 / uid 不一致时返回 {@code null}
     */
    @Nullable
    private UserPrincipal extractProfile(@Nullable SfcJsonResult<UserPrincipal> envelope, Long uid) {
        if (envelope == null || envelope.getCode() != 200 || envelope.getData() == null) {
            log.debug("[身份] 用户档案回源业务失败，降级: uid={}, code={}",
                    uid, envelope == null ? "null" : envelope.getCode());
            return null;
        }
        UserPrincipal profile = envelope.getData();
        if (profile.getId() == null) {
            return null;
        }
        if (!profile.getId().equals(uid)) {
            // 远程接口按入站凭证识别调用者，与信任头 uid 必须自洽；不自洽说明凭证与信任头不是同一人
            log.warn("[身份] 用户档案回源结果的 uid 与信任头不一致，弃用并降级: headerUid={}, remoteUid={}",
                    uid, profile.getId());
            return null;
        }
        if (!SfcStringUtils.hasText(profile.getUsername())) {
            profile.setUsername(FALLBACK_USERNAME_PREFIX + uid);
        }
        return profile;
    }

    /**
     * 缓存条目数超限时清理已过期条目。
     *
     * @param now 当前时间戳（毫秒）
     */
    private void evictExpiredIfNeeded(long now) {
        if (cache.size() <= MAX_CACHE_ENTRIES) {
            return;
        }
        cache.entrySet().removeIf(entry -> entry.getValue().expireAt() <= now);
    }

    /**
     * 用户档案缓存条目。
     *
     * @param profile  用户档案；负缓存（回源失败）时为 {@code null}
     * @param expireAt 过期时间戳（毫秒）
     */
    private record CacheEntry(UserPrincipal profile, long expireAt) {
    }
}
