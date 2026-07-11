package com.sfc.ai.tool;

import org.springframework.ai.tool.annotation.Tool;

import java.time.LocalDateTime;

/**
 * 计算器工具集，提供基本的数学运算工具作为 AI tool call 示例。
 */
public class CommonTools {

    /**
     * 获取当前时间
     */
    @Tool(description = "获取当前时间")
    public String getNowTime() {
        return LocalDateTime.now().toString();
    }

}
