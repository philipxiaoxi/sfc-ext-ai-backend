package com.sfc.ai.core.tool;

import java.util.concurrent.Future;

/**
 * 工具调用执行记录，用于追踪进行中的工具调用，供 STOP 硬中断使用。
 *
 * @param future      工具执行结果 Future
 * @param toolCallId  工具调用唯一 id
 * @param toolName    工具名称
 */
public record ToolExecution(Future<?> future, String toolCallId, String toolName) {
}
