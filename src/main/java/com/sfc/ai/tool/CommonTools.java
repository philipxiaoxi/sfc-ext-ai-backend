package com.sfc.ai.tool;

import com.sfc.cloud.constant.SfcUserConstants;
import com.xiaotao.saltedfishcloud.model.po.file.FileInfo;
import com.xiaotao.saltedfishcloud.service.file.DiskFileSystemManager;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

/**
 * 计算器工具集，提供基本的数学运算工具作为 AI tool call 示例。
 */
public class CommonTools {
    @Autowired
    private DiskFileSystemManager diskFileSystemManager;

    /**
     * 获取当前时间
     */
    @Tool(name = "get_now_time", description = "获取当前时间")
    public String getNowTime() {
        return LocalDateTime.now().toString();
    }

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
