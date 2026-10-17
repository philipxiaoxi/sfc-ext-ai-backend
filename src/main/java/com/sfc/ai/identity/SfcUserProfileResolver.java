package com.sfc.ai.identity;

import com.sfc.common.model.UserPrincipal;
import org.jspecify.annotations.Nullable;

/**
 * 用户档案解析 SPI：以 uid 换取更完整的用户信息（username / admin / email …）。
 *
 * <p><b>为什么需要它：</b>网关注入的信任头（{@code X-Sfc-Uid}）只携带 uid，而本服务在两类场景
 * 还需要 uid 之外的字段：</p>
 * <ul>
 *   <li>拼接 LLM 系统提示词需要 {@code username}；</li>
 *   <li>判定管理员身份需要 {@code admin}（{@code SfcUIDValidator} 对公共资源 uid=0 的写权限
 *       完全依赖它）。</li>
 * </ul>
 * <p>这些字段只有 sfc-core 侧才知道，因此由本 SPI 回源补齐。</p>
 *
 * <p><b>实现约束（重要）：</b>实现方<b>必须</b>保证「拿不到就返回 {@code null}」，
 * 不得向外抛出任何异常。调用点 {@link SfcCurrentUserSupport#currentIsAdmin()} 位于权限校验热路径上，
 * 异常外溢会直接击穿业务功能。</p>
 *
 * @see SfcCoreUserProfileResolver 回源到 sfc-core 的默认实现
 * @see SfcCurrentUserSupport#setProfileResolver(SfcUserProfileResolver)
 */
public interface SfcUserProfileResolver {

    /**
     * 解析指定 uid 的用户档案。
     *
     * @param uid 当前登录用户 uid；不会为 {@code null}（调用方已做前置判定）
     * @return 用户档案（{@code id} 必须等于入参 uid）；无法解析时返回 {@code null}，
     * 表示调用方应降级为「仅 uid 可用、非管理员」
     */
    @Nullable
    UserPrincipal resolve(Long uid);
}
