package com.sfc.ai.core.tool;

import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 工具调用 ID 提供器，用于 {@code ToolCallNotificationAdvisor} 与
 * {@link SfcAgentToolCallbackDecorator} 之间传递工具调用 ID，
 * 确保 {@code TOOL_CALL_START} 与 {@code TOOL_CALL_END} 使用相同的 ID。
 * <p>
 * Advisor 在检测到 tool_calls 时通过 {@link #put()} 生成并存入 UUID；
 * Decorator 在工具执行时通过 {@link #poll()} 取出对应的 UUID。
 * 基于 FIFO 队列保证 Advisor 检测顺序与 Decorator 执行顺序一致。
 */
public class ToolCallIdProvider {

    private final ConcurrentLinkedQueue<String> ids = new ConcurrentLinkedQueue<>();

    /**
     * 生成一个新的 UUID 并存入队列。
     *
     * @return 生成的 UUID
     */
    public String put() {
        String id = UUID.randomUUID().toString();
        ids.add(id);
        return id;
    }

    /**
     * 从队列中取出下一个 UUID。
     *
     * @return UUID，如果队列为空则返回 null
     */
    public String poll() {
        return ids.poll();
    }

    /**
     * 清空队列中所有未使用的 UUID。
     */
    public void clear() {
        ids.clear();
    }
}
