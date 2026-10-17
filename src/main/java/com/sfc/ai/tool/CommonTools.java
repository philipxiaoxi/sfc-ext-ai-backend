package com.sfc.ai.tool;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.time.LocalDateTime;

/**
 * 计算器工具集，提供基本的数学运算工具作为 AI tool call 示例。
 */
public class CommonTools {

    /**
     * 获取当前时间
     *
     * @return 当前时间的字符串表示（ISO-8601 格式）
     */
    @Tool(name = "get_now_time", description = "获取当前时间")
    public String getNowTime() {
        return LocalDateTime.now().toString();
    }

    /**
     * 让 AI 等待指定的毫秒数
     *
     * @param milliseconds 等待的毫秒数
     * @return 固定返回 "ok"，等待过程被中断时返回中断异常消息
     */
    @Tool(name = "sleep", description = "让 AI 等待指定的毫秒数")
    public String sleep(@ToolParam(description = "等待的毫秒") long milliseconds) {
        try {
            Thread.sleep(milliseconds);
        } catch (InterruptedException e) {
            return e.getMessage();
        }
        return "ok";
    }

    /**
     * 获取当前 AI 会话的授权模式。
     *
     * @param ctx 工具上下文，从中读取 fullAuthMode 属性
     * @return "完全授权" 或 "普通授权"
     */
    @Tool(name = "get_auth_mode", description = "获取当前 AI 会话的授权模式，返回完全授权或普通授权")
    public String getAuthMode(ToolContext ctx) {
        boolean fullAuth = Boolean.TRUE.equals(ctx.getContext().get("fullAuthMode"));
        return fullAuth ? "完全授权" : "普通授权";
    }
}
