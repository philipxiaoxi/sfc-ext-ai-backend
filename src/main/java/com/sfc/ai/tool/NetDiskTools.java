package com.sfc.ai.tool;

import com.xiaotao.saltedfishcloud.constant.ResourceProtocol;
import com.xiaotao.saltedfishcloud.constant.UserConstants;
import com.xiaotao.saltedfishcloud.model.dto.ResourceRequest;
import com.xiaotao.saltedfishcloud.model.po.file.FileInfo;
import com.xiaotao.saltedfishcloud.model.param.SimpleFileTransferParam;
import com.xiaotao.saltedfishcloud.service.file.DiskFileSystem;
import com.xiaotao.saltedfishcloud.service.file.DiskFileSystemManager;
import com.xiaotao.saltedfishcloud.utils.DiskFileSystemUtils;
import com.xiaotao.saltedfishcloud.utils.ResourceUtils;
import com.xiaotao.saltedfishcloud.utils.SecureUtils;
import com.xiaotao.saltedfishcloud.utils.StringUtils;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 网盘工具集，提供对公共网盘和私人网盘的常用文件操作功能，
 * 供 AI Agent 调用以管理网盘资源。
 */
public class NetDiskTools {
    @Autowired
    private DiskFileSystemManager diskFileSystemManager;

    /**
     * 将网盘类型字符串解析为用户 ID
     *
     * @param disk 网盘类型，"public" 表示公共网盘，"private" 表示当前用户的私人网盘
     * @return 对应的用户 ID
     * @throws IllegalArgumentException 当 disk 参数无效或 private 模式下未获取到用户上下文时抛出
     */
    private long resolveUid(String disk) {
        return switch (disk) {
            case "public" -> UserConstants.PUBLIC_USER_ID;
            case "private" -> {
                Long uid = SecureUtils.getCurrentUid();
                if (uid == null) {
                    throw new IllegalArgumentException("private 模式需要用户登录");
                }
                yield uid;
            }
            default -> throw new IllegalArgumentException("disk 参数必须为 'public' 或 'private'");
        };
    }

    /**
     * 获取底层的 DiskFileSystem 实例
     */
    private DiskFileSystem getFileSystem() {
        return diskFileSystemManager.getMainFileSystem();
    }

    /**
     * 文件搜索结果
     */
    public record FileSearchResult(
            String disk,
            String path,
            Long size,
            String name
    ) {}

    /**
     * 在网盘中搜索文件。搜索指定目录下匹配文件名正则表达式的文件，支持递归遍历子目录。
     *
     * @param disk       网盘类型，"public" 或 "private"
     * @param path       搜索的起始目录路径，以字符 '/' 开头和作为分隔符
     * @param regex      文件名正则表达式，如 ".*\\.txt" 匹配所有 txt 文件
     * @param maxResults 最大返回结果条数
     * @return 文件搜索结果列表，包含网盘类型、完整路径、文件大小和文件名
     */
    @Tool(name = "search_files", description = "在网盘中搜索文件，支持递归子目录和文件名正则匹配")
    public List<FileSearchResult> searchFiles(
            @ToolParam(description = "网盘类型，'public' 表示公共网盘，'private' 表示私人网盘") String disk,
            @ToolParam(description = "搜索的起始目录路径，以 '/' 开头和作为分隔符") String path,
            @ToolParam(description = "文件名正则表达式，如 '.*\\\\.txt' 匹配所有 txt 文件") String regex,
            @ToolParam(description = "最大返回结果条数") Integer maxResults) throws IOException {
        long uid = resolveUid(disk);
        Pattern pattern = Pattern.compile(regex);
        List<FileSearchResult> results = new ArrayList<>();
        int max = maxResults != null ? maxResults : Integer.MAX_VALUE;

        DiskFileSystemUtils.walk(getFileSystem(), uid, path, new FileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(FileInfo dir, BasicFileAttributes attrs) {
                return results.size() >= max ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(FileInfo file, BasicFileAttributes attrs) {
                if (results.size() >= max) {
                    return FileVisitResult.TERMINATE;
                }
                if (file.isFile() && pattern.matcher(file.getName()).matches()) {
                    results.add(new FileSearchResult(disk, file.getPath(), file.getSize(), file.getName()));
                }
                return results.size() >= max ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(FileInfo file, IOException exc) {
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(FileInfo dir, IOException exc) {
                return results.size() >= max ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
            }
        });

        return results;
    }

    /**
     * 列出指定网盘路径下的文件和目录列表
     *
     * @param disk 网盘类型，"public" 或 "private"
     * @param path 目录路径，以字符 '/' 开头和作为分隔符
     * @return 文件和目录的合并列表
     */
    @Tool(name = "list_files", description = "列出网盘文件列表")
    public List<FileInfo> listFiles(
            @ToolParam(description = "网盘类型，'public' 表示公共网盘，'private' 表示私人网盘") String disk,
            @ToolParam(description = "查询的网盘路径，以字符 '/' 开头和作为分隔符") String path) throws IOException {
        long uid = resolveUid(disk);
        List<FileInfo>[] lists = getFileSystem().getUserFileList(uid, path);
        return Stream.concat(lists[0].stream(), lists[1].stream()).toList();
    }

    /**
     * 重命名网盘中的文件或目录
     *
     * @param disk    网盘类型，"public" 或 "private"
     * @param path    文件或目录所在的父目录路径
     * @param name    原文件名或目录名
     * @param newName 新文件名或目录名
     * @return 操作结果消息
     */
    @Tool(name = "rename", description = "重命名网盘文件或目录")
    public String rename(
            @ToolParam(description = "网盘类型，'public' 表示公共网盘，'private' 表示私人网盘") String disk,
            @ToolParam(description = "文件或目录所在的父目录路径") String path,
            @ToolParam(description = "原文件名或目录名") String name,
            @ToolParam(description = "新文件名或目录名") String newName) {
        try {
            long uid = resolveUid(disk);
            getFileSystem().rename(uid, path, name, newName);
            return "重命名成功";
        } catch (Exception e) {
            return "重命名失败: " + e.getMessage();
        }
    }

    /**
     * 删除网盘中的文件或目录
     *
     * @param disk 网盘类型，"public" 或 "private"
     * @param path 文件或目录所在的父目录路径
     * @param name 要删除的文件名或目录名
     * @return 操作结果消息
     */
    @Tool(name = "delete", description = "删除网盘文件或目录。目录下存在子文件或目录时也能直接删除。对于挂载点则是直接移除挂载点。")
    public String delete(
            @ToolParam(description = "网盘类型，'public' 表示公共网盘，'private' 表示私人网盘") String disk,
            @ToolParam(description = "文件或目录所在的父目录路径") String path,
            @ToolParam(description = "要删除的文件名或目录名") String name) {
        try {
            long uid = resolveUid(disk);
            getFileSystem().deleteFile(uid, path, Collections.singletonList(name));
            return "删除成功";
        } catch (Exception e) {
            return "删除失败: " + e.getMessage();
        }
    }

    /**
     * 移动网盘文件或目录，支持公共网盘与私人网盘之间的双向移动
     *
     * @param sourceDisk  源网盘类型
     * @param sourcePath  源文件或目录所在的父目录路径
     * @param targetDisk  目标网盘类型
     * @param targetPath  目标目录路径
     * @param name        要移动的文件名或目录名
     * @param overwrite   是否覆盖目标路径下的同名文件
     * @return 操作结果消息
     */
    @Tool(name = "move", description = "移动网盘文件或目录，支持公共网盘与私人网盘之间的双向移动")
    public String move(
            @ToolParam(description = "源网盘类型，'public' 或 'private'") String sourceDisk,
            @ToolParam(description = "源文件或目录所在的父目录路径") String sourcePath,
            @ToolParam(description = "目标网盘类型，'public' 或 'private'") String targetDisk,
            @ToolParam(description = "目标目录路径") String targetPath,
            @ToolParam(description = "要移动的文件名或目录名") String name,
            @ToolParam(description = "是否覆盖目标路径下的同名文件") boolean overwrite) {
        try {
            long sourceUid = resolveUid(sourceDisk);
            long targetUid = resolveUid(targetDisk);
            getFileSystem().move(sourceUid, sourcePath, targetUid, targetPath, name, overwrite);
            return "移动成功";
        } catch (Exception e) {
            return "移动失败: " + e.getMessage();
        }
    }

    /**
     * 复制网盘文件，支持公共网盘与私人网盘之间的双向复制
     *
     * @param sourceDisk  源网盘类型
     * @param sourcePath  源目录路径（复制该目录下的文件）
     * @param targetDisk  目标网盘类型
     * @param targetPath  目标目录路径
     * @param name        要复制的文件名。不指定则复制源目录下的所有文件
     * @param overwrite   是否覆盖目标路径下的同名文件
     * @return 操作结果消息
     */
    @Tool(name = "copy", description = "复制网盘文件，支持公共网盘与私人网盘之间的双向复制")
    public String copy(
            @ToolParam(description = "源网盘类型，'public' 或 'private'") String sourceDisk,
            @ToolParam(description = "源目录路径") String sourcePath,
            @ToolParam(description = "目标网盘类型，'public' 或 'private'") String targetDisk,
            @ToolParam(description = "目标目录路径") String targetPath,
            @ToolParam(description = "要复制的文件名，不指定则复制源目录下的所有文件", required = false) String name,
            @ToolParam(description = "是否覆盖目标路径下的同名文件") boolean overwrite) {
        try {
            long sourceUid = resolveUid(sourceDisk);
            long targetUid = resolveUid(targetDisk);
            SimpleFileTransferParam param = new SimpleFileTransferParam();
            param.setSourceUid(sourceUid);
            param.setSourcePath(sourcePath);
            param.setTargetUid(targetUid);
            param.setTargetPath(targetPath);
            param.setIsOverwrite(overwrite);
            if (name != null && !name.isBlank()) {
                param.setFiles(Collections.singletonList(name));
            }
            getFileSystem().copy(param, null);
            return "复制成功";
        } catch (Exception e) {
            return "复制失败: " + e.getMessage();
        }
    }

    /**
     * 递归创建网盘目录（类似 mkdir -p），路径中不存在的父级目录会被一并创建
     *
     * @param disk 网盘类型，"public" 或 "private"
     * @param path 要创建的目录路径，以字符 '/' 开头和作为分隔符
     * @return 操作结果消息
     */
    @Tool(name = "mkdirs", description = "递归创建网盘目录（类似 mkdir -p），父级目录不存在时也会一并创建")
    public String mkdirs(
            @ToolParam(description = "网盘类型，'public' 表示公共网盘，'private' 表示私人网盘") String disk,
            @ToolParam(description = "要创建的目录路径，以字符 '/' 开头和作为分隔符") String path) {
        try {
            long uid = resolveUid(disk);
            getFileSystem().mkdirs(uid, path);
            return "目录创建成功";
        } catch (Exception e) {
            return "目录创建失败: " + e.getMessage();
        }
    }

    /**
     * 获取网盘文件的下载链接
     *
     * @param disk 网盘类型，"public" 表示公共网盘，"private" 表示私人网盘
     * @param path 文件所在的目录路径，以字符 '/' 开头和作为分隔符
     * @param name 文件名
     * @return 下载链接（以 / 开头）
     */
    @Tool(name = "get_download_link", description = "获取网盘文件的下载链接，返回以 '/' 开头的路径")
    public String getDownloadLink(
            @ToolParam(description = "网盘类型，'public' 表示公共网盘，'private' 表示私人网盘") String disk,
            @ToolParam(description = "文件所在的目录路径，以字符 '/' 开头和作为分隔符") String path,
            @ToolParam(description = "文件名") String name) {
        long uid = resolveUid(disk);
        ResourceRequest resourceRequest = ResourceRequest.builder()
                .protocol(ResourceProtocol.MAIN)
                .targetId(String.valueOf(uid))
                .path(path)
                .name(name)
                .build();
        return UriComponentsBuilder.fromPath("/api/resource/{uid}/get")
                .queryParam("protocol", resourceRequest.getProtocol())
                .queryParam("targetId", resourceRequest.getTargetId())
                .queryParam("path", resourceRequest.getPath())
                .queryParam("name", resourceRequest.getName())
                .encode()
                .buildAndExpand(uid)
                .toUriString();
    }

    /**
     * 校验文件是否为纯文本文件。通过读取文件头部字节检测是否存在 NUL 字节来判断。
     * 若文件不存在则跳过校验（可能为新建文件）。
     *
     * @param uid  用户 ID
     * @param path 文件所在目录路径
     * @param name 文件名
     * @throws IllegalArgumentException 当文件不是纯文本文件时抛出
     */
    private void validateTextFile(long uid, String path, String name) {
        Resource resource;
        try {
            resource = getFileSystem().getResource(uid, path, name);
        } catch (IOException e) {
            throw new RuntimeException("无法读取文件: " + StringUtils.appendPath(path, name), e);
        }
        if (resource == null) {
            return;
        }
        try (InputStream is = resource.getInputStream()) {
            byte[] buffer = new byte[8192];
            int bytesRead = is.read(buffer);
            if (bytesRead > 0 && containsNullByte(buffer, bytesRead)) {
                throw new IllegalArgumentException("不是纯文本文件: " + StringUtils.appendPath(path, name));
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("验证文件类型失败", e);
        }
    }

    /**
     * 检查字节数组中是否包含 NUL（0x00）字节，用于判断是否为二进制数据
     */
    private static boolean containsNullByte(byte[] data, int len) {
        for (int i = 0; i < len; i++) {
            if (data[i] == 0x00) {
                return true;
            }
        }
        return false;
    }

    /**
     * 读取网盘文本文件的完整内容，返回按行分割的列表，每项为 [行号, 行内容]
     */
    private List<Object[]> readAllLines(long uid, String path, String name) throws IOException {
        Resource resource = getFileSystem().getResource(uid, path, name);
        if (resource == null) {
            throw new IllegalArgumentException("文件不存在: " + StringUtils.appendPath(path, name));
        }
        String content = ResourceUtils.resourceToString(resource);
        List<Object[]> lines = new ArrayList<>();
        int lineStart = 0;
        int lineNum = 1;
        for (int i = 0; i < content.length(); i++) {
            if (content.charAt(i) == '\n') {
                lines.add(new Object[]{lineNum, content.substring(lineStart, i + 1)});
                lineStart = i + 1;
                lineNum++;
            }
        }
        if (lineStart < content.length()) {
            lines.add(new Object[]{lineNum, content.substring(lineStart)});
        }
        return lines;
    }

    /**
     * 读取网盘文本文件按行号范围读取内容，并附带行号返回。
     * 建议每次读取不超过200行，每次读取范围不能超过1000行。
     *
     * @param disk     网盘类型，"public" 或 "private"
     * @param path     文件所在目录路径
     * @param name     文件名
     * @param startLine 起始行号（从1开始）
     * @param endLine   结束行号（包含）
     * @return 行号与行内容的二元组列表，如 [[1, "第一行内容\n"], [2, "第二行内容"]]
     */
    @Tool(name = "read_text_file", description = "读取网盘文本文件按行号范围读取内容，建议每次读取不超过200行（推荐按 startLine~endLine 分段读取），每次读取范围不能超过1000行")
    public List<Object[]> readTextFile(
            @ToolParam(description = "网盘类型，'public' 表示公共网盘，'private' 表示私人网盘") String disk,
            @ToolParam(description = "文件所在目录路径，以 '/' 开头和作为分隔符") String path,
            @ToolParam(description = "文件名") String name,
            @ToolParam(description = "起始行号（从1开始）") Integer startLine,
            @ToolParam(description = "结束行号（包含，推荐 startLine 与 endLine 间隔不超过200行）") Integer endLine) throws IOException {
        long uid = resolveUid(disk);
        validateTextFile(uid, path, name);

        if (startLine == null || startLine < 1) {
            throw new IllegalArgumentException("startLine 必须大于等于1");
        }
        if (endLine == null || endLine < startLine) {
            throw new IllegalArgumentException("endLine 必须大于等于 startLine");
        }
        if (endLine - startLine + 1 > 1000) {
            throw new IllegalArgumentException("读取行数范围不能超过1000行");
        }

        Resource resource = getFileSystem().getResource(uid, path, name);
        if (resource == null) {
            throw new IllegalArgumentException("文件不存在: " + StringUtils.appendPath(path, name));
        }

        String content = ResourceUtils.resourceToString(resource);
        List<Object[]> result = new ArrayList<>();

        int lineStart = 0;
        int lineNum = 1;
        for (int i = 0; i < content.length(); i++) {
            if (content.charAt(i) == '\n') {
                if (lineNum >= startLine) {
                    result.add(new Object[]{lineNum, content.substring(lineStart, i + 1)});
                }
                lineStart = i + 1;
                lineNum++;
                if (lineNum > endLine) {
                    break;
                }
            }
        }
        if (lineStart < content.length() && lineNum >= startLine && lineNum <= endLine) {
            result.add(new Object[]{lineNum, content.substring(lineStart)});
        }

        return result;
    }

    /**
     * 全覆盖写入网盘文本文件，文件不存在则自动创建
     *
     * @param disk    网盘类型，"public" 或 "private"
     * @param path    文件所在目录路径
     * @param name    文件名
     * @param content 要写入的完整文本内容
     * @return 操作结果消息
     */
    @Tool(name = "write_text_file", description = "全覆盖写入网盘文本文件，文件不存在则自动创建")
    public String writeTextFile(
            @ToolParam(description = "网盘类型，'public' 表示公共网盘，'private' 表示私人网盘") String disk,
            @ToolParam(description = "文件所在目录路径，以 '/' 开头和作为分隔符") String path,
            @ToolParam(description = "文件名") String name,
            @ToolParam(description = "要写入的完整文本内容") String content) {
        try {
            long uid = resolveUid(disk);
            validateTextFile(uid, path, name);

            FileInfo fileInfo = new FileInfo();
            fileInfo.setName(name);
            fileInfo.setUid(uid);
            byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
            fileInfo.setSize((long) bytes.length);
            fileInfo.setStreamSource(() -> new ByteArrayInputStream(bytes));

            getFileSystem().saveFileByStream(fileInfo, path, os -> DiskFileSystemUtils.saveFile(fileInfo, os));
            return "写入成功";
        } catch (Exception e) {
            return "写入失败: " + e.getMessage();
        }
    }

    /**
     * 在网盘文本文件的指定行号前插入新内容
     *
     * @param disk       网盘类型，"public" 或 "private"
     * @param path       文件所在目录路径
     * @param name       文件名
     * @param content    要插入的文本内容
     * @param lineNumber 插入到该行之前，从1开始；若为总行数+1则追加到文件末尾
     * @return 操作结果消息
     */
    @Tool(name = "insert_text_line", description = "在网盘文本文件的指定行号前插入新内容")
    public String insertTextLine(
            @ToolParam(description = "网盘类型，'public' 表示公共网盘，'private' 表示私人网盘") String disk,
            @ToolParam(description = "文件所在目录路径，以 '/' 开头和作为分隔符") String path,
            @ToolParam(description = "文件名") String name,
            @ToolParam(description = "要插入的文本内容") String content,
            @ToolParam(description = "插入到该行之前（从1开始），若为总行数+1则追加到文件末尾") Integer lineNumber) {
        try {
            long uid = resolveUid(disk);
            validateTextFile(uid, path, name);
            if (lineNumber == null || lineNumber < 1) {
                return "插入失败: lineNumber 必须大于等于1";
            }

            List<Object[]> lines = readAllLines(uid, path, name);
            int totalLines = lines.size();
            if (lineNumber > totalLines + 1) {
                return "插入失败: lineNumber 超出文件总行数（" + totalLines + "），最大为 " + (totalLines + 1);
            }

            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < lines.size(); i++) {
                int currentLine = i + 1;
                if (currentLine == lineNumber) {
                    sb.append(content);
                }
                sb.append((String) lines.get(i)[1]);
            }
            if (lineNumber > totalLines) {
                sb.append(content);
            }

            byte[] bytes = sb.toString().getBytes(StandardCharsets.UTF_8);
            FileInfo fileInfo = new FileInfo();
            fileInfo.setName(name);
            fileInfo.setUid(uid);
            fileInfo.setSize((long) bytes.length);
            fileInfo.setStreamSource(() -> new ByteArrayInputStream(bytes));
            getFileSystem().saveFileByStream(fileInfo, path, os -> DiskFileSystemUtils.saveFile(fileInfo, os));
            return "插入成功";
        } catch (Exception e) {
            return "插入失败: " + e.getMessage();
        }
    }

    /**
     * 替换网盘文本文件指定行号范围内的内容
     *
     * @param disk      网盘类型，"public" 或 "private"
     * @param path      文件所在目录路径
     * @param name      文件名
     * @param content   替换后的文本内容
     * @param startLine 起始行号（从1开始）
     * @param endLine   结束行号（包含）
     * @return 操作结果消息
     */
    @Tool(name = "replace_text_lines", description = "替换网盘文本文件指定行号范围内的内容")
    public String replaceTextLines(
            @ToolParam(description = "网盘类型，'public' 表示公共网盘，'private' 表示私人网盘") String disk,
            @ToolParam(description = "文件所在目录路径，以 '/' 开头和作为分隔符") String path,
            @ToolParam(description = "文件名") String name,
            @ToolParam(description = "替换后的文本内容") String content,
            @ToolParam(description = "起始行号（从1开始）") Integer startLine,
            @ToolParam(description = "结束行号（包含）") Integer endLine) {
        try {
            long uid = resolveUid(disk);
            validateTextFile(uid, path, name);
            if (startLine == null || startLine < 1) {
                return "替换失败: startLine 必须大于等于1";
            }
            if (endLine == null || endLine < startLine) {
                return "替换失败: endLine 必须大于等于 startLine";
            }

            List<Object[]> lines = readAllLines(uid, path, name);
            int totalLines = lines.size();
            if (startLine > totalLines) {
                return "替换失败: startLine 超出文件总行数（" + totalLines + "）";
            }
            if (endLine > totalLines) {
                return "替换失败: endLine 超出文件总行数（" + totalLines + "）";
            }

            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < lines.size(); i++) {
                int currentLine = i + 1;
                if (currentLine >= startLine && currentLine <= endLine) {
                    if (currentLine == startLine) {
                        sb.append(content);
                    }
                } else {
                    sb.append((String) lines.get(i)[1]);
                }
            }

            byte[] bytes = sb.toString().getBytes(StandardCharsets.UTF_8);
            FileInfo fileInfo = new FileInfo();
            fileInfo.setName(name);
            fileInfo.setUid(uid);
            fileInfo.setSize((long) bytes.length);
            fileInfo.setStreamSource(() -> new ByteArrayInputStream(bytes));
            getFileSystem().saveFileByStream(fileInfo, path, os -> DiskFileSystemUtils.saveFile(fileInfo, os));
            return "替换成功";
        } catch (Exception e) {
            return "替换失败: " + e.getMessage();
        }
    }
}
