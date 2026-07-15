package com.sfc.ai.tool;

import com.xiaotao.saltedfishcloud.constant.UserConstants;
import com.xiaotao.saltedfishcloud.model.po.file.FileInfo;
import com.xiaotao.saltedfishcloud.service.file.DiskFileSystemManager;
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
    @Tool(description = "获取当前时间")
    public String getNowTime() {
        return LocalDateTime.now().toString();
    }

    @Tool(description = "让 AI 等待指定的毫秒数")
    public String sleep(@ToolParam(description = "等待的毫秒") long milliseconds) {
        try {
            Thread.sleep(milliseconds);
        } catch (InterruptedException e) {
            return e.getMessage();
        }
        return "ok";
    }
}
