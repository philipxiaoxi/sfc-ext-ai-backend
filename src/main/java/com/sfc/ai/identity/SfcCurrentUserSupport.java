package com.sfc.ai.identity;

import com.sfc.common.model.UserPrincipal;
import com.sfc.cloud.trust.SfcTrustHeaders;
import com.sfc.common.utils.SfcStringUtils;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * sfc-ai-service 的「当前用户」身份支持。
 *
 * <p><b>背景：</b>本服务原为宿主插件 {@code sfc-ext-ai}，身份取自宿主的 Spring Security
 * 上下文（宿主工具类 {@code SecureUtils}）。Phase 2 迁为独立微服务后
 * 本服务<b>没有</b> Spring Security，身份只有一个来源：网关注入的信任头
 * {@link SfcTrustHeaders#UID}（{@code X-Sfc-Uid}）。本类把该口径收敛成一处，
 * 供控制器、工具执行与管理端校验共用。</p>
 *
 * <p><b>两段式身份解析（顺序固定）：</b></p>
 * <ol>
 *   <li><b>线程绑定</b>：{@link #bind(UserPrincipal)} 显式绑定到当前线程。工具调用可能运行在
 *       与请求线程无关的虚拟线程上（见 {@code ToolExecutionManager}），此时请求上下文已丢失，
 *       只能靠绑定传递身份——这是宿主 {@code SecureUtils.bindUser} 的等价物。</li>
 *   <li><b>请求上下文</b>：{@link RequestContextHolder} 中的当前 HTTP 请求 → 信任头
 *       {@code X-Sfc-Uid}。</li>
 * </ol>
 *
 * <p><b>匿名口径：</b>网关对「无凭证 / 凭证无效」的请求注入 {@link SfcTrustHeaders#ANONYMOUS_UID}
 * （{@code "0"}），本类将其映射为 {@code null}（未登录）——与宿主
 * {@code SecureUtils.getCurrentUid()} 在未认证时返回 {@code null} 的口径一致，
 * 从而保证 {@code SfcUIDValidator} 对公共资源「读放行、写要求管理员」的判定不变。</p>
 *
 * <p><b>失败降级（绝不抛异常）：</b>无 HTTP 上下文（定时任务线程、工具执行线程、WebSocket
 * 消息线程）时 {@link #getCurrentUid()} 返回 {@code null}、{@link #currentIsAdmin()} 返回
 * {@code false}；远程回源失败同样降级。调用方无需（也不应）做异常兜底。</p>
 *
 * <p><b>为什么是静态工具类而不是 Spring Bean：</b>调用点跨越三种无法依赖注入的位置——
 * ① WebSocket 通道（{@code WebSocketMessageChannel} 由 {@code AiChatWebSocketHandler}
 * 以 {@code new} 构造，无法改造其构造签名）；② 工具执行线程（需要静态 ThreadLocal）；
 * ③ {@link com.sfc.cloud.data.identity.CurrentUserProvider} 这一静态 SPI。
 * 因此与仓库既有的 {@code SfcMapperHolder} / {@code CurrentUserHolder} 保持一致，
 * 采用「静态工具 + 启动期注册可选依赖（{@link #setProfileResolver}）」的形态。</p>
 *
 * @see SfcUserProfileResolver
 * @see SfcCurrentUserProvider
 */
@Slf4j
public final class SfcCurrentUserSupport {

    /** 当前线程显式绑定的用户；由 {@link #bind(UserPrincipal)} 写入、{@link #unbind()} 清除 */
    private static final ThreadLocal<UserPrincipal> BOUND_USER = new ThreadLocal<>();

    /** 用户档案回源解析器；由启动期 {@link #setProfileResolver(SfcUserProfileResolver)} 注册，未注册时为 null */
    private static volatile SfcUserProfileResolver profileResolver;

    /** 降级用户名前缀：无法回源取得真实用户名时使用 */
    private static final String FALLBACK_USERNAME_PREFIX = "uid:";

    /** 工具类不允许实例化 */
    private SfcCurrentUserSupport() {
    }

    /**
     * 注册用户档案回源解析器（启动期调用一次）。
     *
     * @param resolver 解析器；传 {@code null} 表示关闭回源（username/admin 一律降级）
     */
    public static void setProfileResolver(@Nullable SfcUserProfileResolver resolver) {
        profileResolver = resolver;
        if (resolver == null) {
            log.info("[身份] 未注册用户档案回源解析器，username/admin 将一律降级（uid 不受影响）");
        } else {
            log.info("[身份] 已注册用户档案回源解析器: {}", resolver.getClass().getName());
        }
    }

    // ── 当前用户读取 ──

    /**
     * 获取当前登录用户的 uid。
     *
     * @return 当前登录用户 uid；未登录（匿名）、无 HTTP 上下文或信任头非法时返回 {@code null}
     */
    @Nullable
    public static Long getCurrentUid() {
        UserPrincipal bound = BOUND_USER.get();
        if (bound != null) {
            return bound.getId();
        }
        return resolveUidFromRequest();
    }

    /**
     * 获取当前登录用户的用户主体。
     *
     * <p>线程已绑定时直接返回绑定对象（不做远程回源）；否则以信任头的 uid 回源补齐
     * username/admin 等字段，回源不可用时降级为「仅 uid + 占位用户名 + 非管理员」的最小主体。</p>
     *
     * @return 当前登录用户主体；未登录或信任头非法时返回 {@code null}
     */
    @Nullable
    public static UserPrincipal getCurrentUser() {
        UserPrincipal bound = BOUND_USER.get();
        if (bound != null) {
            return bound;
        }
        return resolveUser(resolveUidFromRequest());
    }

    /**
     * 判断当前登录用户是否为系统管理员。
     *
     * <p>本方法<b>不会</b>向外抛异常：无身份、无 HTTP 上下文、远程回源失败一律返回 {@code false}
     * （fail-closed，与 {@code CurrentUserHolder} 的失效策略一致）。</p>
     *
     * @return 是管理员返回 {@code true}；其余情况返回 {@code false}
     */
    public static boolean currentIsAdmin() {
        UserPrincipal bound = BOUND_USER.get();
        if (bound != null) {
            return bound.isAdmin();
        }
        Long uid = resolveUidFromRequest();
        if (uid == null) {
            // 未登录 / 匿名：网关不会为无凭证请求签发真实 uid，直接 fail-closed，且省下一次远程回源
            return false;
        }
        UserPrincipal profile = resolveProfile(uid);
        return profile != null && profile.isAdmin();
    }

    // ── 线程绑定 ──

    /**
     * 将指定用户绑定到当前线程（等价于宿主 {@code SecureUtils.bindUser}）。
     *
     * <p>用于工具执行等「脱离请求上下文」的线程：绑定后该线程内
     * {@link #getCurrentUid()} / {@link #getCurrentUser()} / {@link #currentIsAdmin()}
     * 均以绑定对象为准。使用完毕必须在 {@code finally} 中调用 {@link #unbind()}，
     * 否则线程被复用时会串号。</p>
     *
     * @param user 待绑定的用户；为 {@code null} 时等价于 {@link #unbind()}
     */
    public static void bind(@Nullable UserPrincipal user) {
        if (user == null) {
            unbind();
            return;
        }
        BOUND_USER.set(user);
    }

    /**
     * 清除当前线程的用户绑定（等价于宿主 {@code SecureUtils.unbind}）。
     */
    public static void unbind() {
        BOUND_USER.remove();
    }

    /**
     * 获取当前线程显式绑定的用户（不含请求上下文解析）。
     *
     * @return 绑定对象；未绑定时返回 {@code null}
     */
    @Nullable
    public static UserPrincipal getBoundUser() {
        return BOUND_USER.get();
    }

    // ── 供 WebSocket 等无请求上下文的传输层使用 ──

    /**
     * 从一组 HTTP 请求头解析当前用户主体。
     *
     * <p>WebSocket 场景专用：握手是一次普通 HTTP 请求（网关注入信任头），但后续每条消息都
     * 运行在独立的容器线程上、没有 {@link RequestContextHolder} 上下文，
     * 因此只能从 {@code WebSocketSession#getHandshakeHeaders()} 解析。</p>
     *
     * @param headers 握手请求头；可为 {@code null}
     * @return 用户主体；信任头缺失 / 匿名 / 非法时返回 {@code null}
     */
    @Nullable
    public static UserPrincipal resolveUserFromHeaders(@Nullable HttpHeaders headers) {
        if (headers == null) {
            return null;
        }
        return resolveUser(parseUidHeader(headers.getFirst(SfcTrustHeaders.UID)));
    }

    /**
     * 以已知 uid 构造用户主体（能回源补齐则补齐，否则降级）。
     *
     * @param uid 用户 uid；为 {@code null} 时返回 {@code null}
     * @return 用户主体；{@code uid} 为 {@code null} 时返回 {@code null}
     */
    @Nullable
    public static UserPrincipal resolveUser(@Nullable Long uid) {
        if (uid == null) {
            return null;
        }
        UserPrincipal profile = resolveProfile(uid);
        if (profile != null) {
            return profile;
        }
        // 降级：uid 仍可用，username 以占位符兜底，admin 恒为 false（fail-closed）
        UserPrincipal minimal = new UserPrincipal();
        minimal.setId(uid);
        minimal.setUsername(FALLBACK_USERNAME_PREFIX + uid);
        minimal.setAdmin(false);
        return minimal;
    }

    /**
     * 解析信任头的原始取值。
     *
     * @param rawUid 信任头原文；可为 {@code null}
     * @return 登录用户 uid；信任头缺失、为 {@link SfcTrustHeaders#ANONYMOUS_UID}（匿名）、
     * 非法或为负数时返回 {@code null}
     */
    @Nullable
    public static Long parseUidHeader(@Nullable String rawUid) {
        if (!SfcStringUtils.hasText(rawUid)) {
            return null;
        }
        String trimmed = rawUid.trim();
        if (SfcTrustHeaders.ANONYMOUS_UID.equals(trimmed)) {
            // 无凭证 / 凭证无效：按「未登录」处理，与宿主 SecureUtils.getCurrentUid() 的 null 口径对齐
            return null;
        }
        try {
            long uid = Long.parseLong(trimmed);
            return uid < 0 ? null : uid;
        } catch (NumberFormatException e) {
            log.debug("[身份] 信任头 {} 取值非法，按未登录处理", SfcTrustHeaders.UID);
            return null;
        }
    }

    /**
     * 获取当前线程绑定的 HTTP 请求。
     *
     * @return 当前请求；无 HTTP 上下文（非 Servlet 上下文、定时任务线程、工具执行线程、
     * WebSocket 消息线程）时返回 {@code null}
     */
    @Nullable
    public static HttpServletRequest currentRequest() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes instanceof ServletRequestAttributes servletAttributes) {
            return servletAttributes.getRequest();
        }
        return null;
    }

    /**
     * 用户显示名：优先真实用户名，其次 {@code uid:<id>} 占位，无身份时为「未登录」。
     *
     * <p>供 LLM 系统提示词等纯展示场景使用，避免调用点自行判空导致 NPE。</p>
     *
     * @param user 用户主体；可为 {@code null}
     * @return 展示用用户名
     */
    public static String displayName(@Nullable UserPrincipal user) {
        if (user == null) {
            return "未登录";
        }
        if (SfcStringUtils.hasText(user.getUsername())) {
            return user.getUsername();
        }
        return user.getId() == null ? "未登录" : FALLBACK_USERNAME_PREFIX + user.getId();
    }

    // ── 内部实现 ──

    /**
     * 从当前 HTTP 请求的信任头解析 uid。
     *
     * @return 登录用户 uid；无请求上下文或信任头缺失/匿名/非法时返回 {@code null}
     */
    @Nullable
    private static Long resolveUidFromRequest() {
        HttpServletRequest request = currentRequest();
        if (request == null) {
            return null;
        }
        return parseUidHeader(request.getHeader(SfcTrustHeaders.UID));
    }

    /**
     * 调用回源解析器取得用户档案，并吞掉一切异常（热路径安全保证）。
     *
     * @param uid 用户 uid
     * @return 用户档案；解析器未注册、解析失败或返回 {@code null} 时返回 {@code null}
     */
    @Nullable
    private static UserPrincipal resolveProfile(Long uid) {
        SfcUserProfileResolver resolver = profileResolver;
        if (resolver == null) {
            return null;
        }
        try {
            return resolver.resolve(uid);
        } catch (RuntimeException e) {
            // SPI 约定要求实现方自行返回 null；这里是最后的兜底，绝不让异常击穿权限校验
            log.warn("[身份] 用户档案回源解析异常，降级为 uid-only 身份: uid={}, cause={}", uid, e.toString());
            return null;
        }
    }
}
