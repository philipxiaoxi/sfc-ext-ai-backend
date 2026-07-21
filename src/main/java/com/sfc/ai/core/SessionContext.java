package com.sfc.ai.core;

import com.sfc.ai.model.chat.session.ChatSession;
import com.xiaotao.saltedfishcloud.model.po.UserPrincipal;
import lombok.Getter;
import lombok.Setter;
import reactor.core.Disposable;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * AI 会话执行上下文。
 * <p>
 * 封装单次 WebSocket 连接生命周期内的可变状态：
 * <ul>
 *   <li>会话状态（{@link ChatSession}）</li>
 *   <li>LLM 响应流句柄（{@link Disposable}）</li>
 *   <li>异步标题生成 Future</li>
 *   <li>STOP / DONE 发送去重标记</li>
 * </ul>
 * 提供统一的 {@link #dispose()} 方法释放所有资源。
 */
public class SessionContext {

    @Getter
    private ChatSession chatSession;

    @Setter
    private Disposable disposable;

    @Setter
    private CompletableFuture<?> titleFuture;

    private final AtomicBoolean stopped = new AtomicBoolean(false);
    private final AtomicBoolean doneSent = new AtomicBoolean(false);

    // ── 会话生命周期 ──

    /**
     * 初始化新会话。
     *
     * @param sessionId 会话 ID
     * @param user      当前认证用户
     * @param isNew     是否首次对话
     */
    public void startSession(String sessionId, UserPrincipal user, boolean isNew) {
        this.chatSession = new ChatSession(sessionId, user, isNew);
    }

    /**
     * 当前是否已存在有效会话。
     */
    public boolean hasSession() {
        return chatSession != null;
    }

    public String getConversationId() {
        return chatSession.getSessionId();
    }

    public UserPrincipal getUser() {
        return chatSession.getUser();
    }

    public boolean isFirstChat() {
        return chatSession.isFirstChat();
    }

    public void markFirstChatDone() {
        chatSession.markFirstChatDone();
    }

    // ── 单次 CHAT 流状态 ──

    /**
     * 重置 CHAT 流状态标记，准备发起新的 LLM 请求。
     */
    public void startChat() {
        stopped.set(false);
        doneSent.set(false);
    }

    /**
     * 标记为已停止，用于抑制 doOnError 中的错误通知。
     */
    public void stop() {
        stopped.set(true);
    }

    /**
     * 是否已被用户或系统停止。
     */
    public boolean isStopped() {
        return stopped.get();
    }

    /**
     * CAS 尝试发送 DONE 消息，保证只发送一次。
     *
     * @return true 如果调用方应当发送 DONE，false 表示已发送或已标记
     */
    public boolean trySendDone() {
        return doneSent.compareAndSet(false, true);
    }

    // ── 统一资源释放 ──

    /**
     * 释放所有与当前会话相关的资源（响应流、异步任务）。
     * 调用后可通过 {@link #clearSession()} 清除会话引用。
     */
    public void dispose() {
        if (disposable != null && !disposable.isDisposed()) {
            disposable.dispose();
        }
        if (titleFuture != null && !titleFuture.isDone()) {
            titleFuture.cancel(true);
        }
    }

    /**
     * 清除会话引用。通常在 {@link #dispose()} 之后且不再需要会话数据时调用。
     */
    public void clearSession() {
        this.chatSession = null;
    }
}
