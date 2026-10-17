package com.sfc.ai.core.tool;

import com.sfc.common.model.UserPrincipal;
import lombok.Data;
import org.springframework.ai.tool.ToolCallback;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

/**
 * 待用户批准的敏感工具调用记录。
 * <p>
 * 持有阻塞式批准决策句柄 {@link #decision}：敏感工具调用发起方通过
 * {@link #awaitDecision()} 阻塞等待用户决策，用户批准/拒绝时由
 * {@link #complete(boolean)} 解除阻塞，连接关闭或用户中断时由 {@link #cancel()}
 * 解除阻塞并取消执行。
 */
@Data
public class PendingApproval {
    private String toolCallId;
    private String toolName;
    private Map<String, Object> arguments;
    private String purpose;
    private ToolCallback callback;
    private Instant createdAt;

    /**
     * 发起工具调用的用户。
     *
     * <p>批准执行时由 {@code ToolExecutionManager} 经
     * {@code SfcCurrentUserSupport#bind(UserPrincipal)} 绑定到执行线程，
     * 供工具内部以 {@code SfcCurrentUserSupport#getCurrentUid()} 读取（微服务侧无 Spring Security 上下文）。</p>
     */
    private UserPrincipal user;

    /**
     * 批准决策句柄：{@code complete(true)} 批准、{@code complete(false)} 拒绝、
     * {@code complete(null)} 取消。
     */
    private final CompletableFuture<Boolean> decision = new CompletableFuture<>();

    /**
     * 构造待批准的敏感工具调用记录。
     *
     * @param toolCallId 工具调用 ID
     * @param toolName   工具名称
     * @param arguments  工具调用参数
     * @param purpose    工具用途说明
     * @param callback   工具回调，批准后执行
     * @param createdAt  发起时间
     * @param user       发起调用的用户
     */
    public PendingApproval(String toolCallId, String toolName, Map<String, Object> arguments,
                           String purpose, ToolCallback callback, Instant createdAt, UserPrincipal user) {
        this.toolCallId = toolCallId;
        this.toolName = toolName;
        this.arguments = arguments;
        this.purpose = purpose;
        this.callback = callback;
        this.createdAt = createdAt;
        this.user = user;
    }

    /**
     * 阻塞等待用户对本次敏感工具调用的决策。
     *
     * @return true 表示批准执行，false 表示拒绝执行，null 表示被取消（连接关闭/用户中断）
     * @throws InterruptedException 等待期间线程被中断
     */
    public Boolean awaitDecision() throws InterruptedException {
        try {
            return decision.get();
        } catch (ExecutionException e) {
            return null;
        }
    }

    /**
     * 完成批准决策，解除阻塞中的调用线程。
     *
     * @param approved true 表示用户批准执行，false 表示用户拒绝
     */
    public void complete(boolean approved) {
        decision.complete(approved);
    }

    /**
     * 取消本次批准等待（连接关闭、用户中断、会话切换等场景），使阻塞中的调用线程解除阻塞。
     */
    public void cancel() {
        decision.complete(null);
    }
}
