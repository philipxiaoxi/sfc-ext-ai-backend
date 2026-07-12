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

    /**
     * 列出公共网盘的文件列表
     * @param path  目录路径
     */
    @Tool(description = "列出公共网盘的文件列表")
    public List<FileInfo> listPublicNetDiskFiles(@ToolParam(description = "查询的网盘路径，以字符 '/' 开头和作为分隔符") String path) throws IOException {
        List<FileInfo>[] userFileList = diskFileSystemManager.getMainFileSystem().getUserFileList(UserConstants.PUBLIC_USER_ID, path);
        return Stream.concat(
                userFileList[0].stream(),
                userFileList[1].stream()
        ).toList();
    }
}
