package com.sfc.ai.tool;

import com.xiaotao.saltedfishcloud.model.po.file.FileInfo;
import com.xiaotao.saltedfishcloud.service.file.DiskFileSystem;
import com.xiaotao.saltedfishcloud.service.file.DiskFileSystemManager;
import com.xiaotao.saltedfishcloud.utils.DiskFileSystemUtils;
import com.xiaotao.saltedfishcloud.utils.ResourceUtils;
import org.jspecify.annotations.NonNull;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 网盘全文搜索工具集，提供在网盘文本文件中按正则表达式搜索内容的功能，
 * 供 AI Agent 在代码编写场景下进行文本内容全文搜索。
 * <p>
 * 对标 OpenCode / Claude Code / Codex 的 grep 工具，支持：
 * <ul>
 *   <li>正则表达式全文匹配</li>
 *   <li>文件名 glob 过滤</li>
 *   <li>结果数量限制</li>
 *   <li>自动跳过二进制文件</li>
 * </ul>
 */
public class TextSearchTools {

    @Autowired
    private DiskFileSystemManager diskFileSystemManager;

    /**
     * 获取底层的 DiskFileSystem 实例
     */
    private DiskFileSystem getFileSystem() {
        return diskFileSystemManager.getMainFileSystem();
    }

    /**
     * 全文搜索结果，包含匹配所在文件路径、行号和行内容
     */
    public record TextSearchResult(
            /** 网盘类型 */
            String disk,
            /** 文件所在目录路径 */
            String path,
            /** 文件名 */
            String name,
            /** 匹配行号（从1开始） */
            Integer lineNumber,
            /** 匹配行内容（含换行符） */
            String lineContent
    ) {
    }

    /**
     * 在网盘文本文件中全文搜索匹配正则表达式的内容，递归遍历子目录，逐行匹配并返回结果。
     *
     * @param disk       网盘类型，"public" 或 "private"
     * @param path       搜索的起始目录路径，以字符 '/' 开头和作为分隔符
     * @param pattern    正则表达式搜索模式，如 {@code function\s+\w+} 搜索函数定义
     * @param include    文件名过滤规则，支持通配符 {@code *} 和 {@code ?}，如 {@code *.java} 限定 Java 文件；为 null 或空时不限制文件类型
     * @param maxResults 最大返回结果条数
     * @return 文本搜索结果列表，包含网盘类型、文件路径、文件名、匹配行号及行内容
     * @throws IllegalArgumentException 当正则表达式模式无效时抛出
     * @throws IOException              当遍历文件系统过程发生 IO 异常时抛出
     */
    @Tool(name = "search_text_content",
            description = "在网盘文本文件中全文搜索匹配正则表达式的内容，递归遍历子目录，返回文件路径、行号和匹配行内容")
    public List<TextSearchResult> searchTextContent(
            @ToolParam(description = "网盘类型，'public' 表示公共网盘，'private' 表示私人网盘") String disk,
            @ToolParam(description = "搜索的起始目录路径，以 '/' 开头和作为分隔符") String path,
            @ToolParam(description = "正则表达式搜索模式，如 'function\\\\s+\\\\w+' 搜索函数定义") String pattern,
            @ToolParam(description = "文件名过滤规则，支持通配符 * 和 ?，如 '*.java'、'*.md' 限定文件类型", required = false) String include,
            @ToolParam(description = "最大返回结果条数") Integer maxResults) throws IOException {

        long uid = NetDiskToolUtils.resolveUid(disk);
        final Pattern regex;
        try {
            regex = Pattern.compile(pattern);
        } catch (PatternSyntaxException e) {
            throw new IllegalArgumentException("无效的正则表达式: " + pattern, e);
        }

        final int max = maxResults != null ? maxResults : Integer.MAX_VALUE;
        final List<TextSearchResult> results = new ArrayList<>();

        DiskFileSystemUtils.walk(getFileSystem(), uid, path, new FileVisitor<>() {
            @Override
            public @NonNull FileVisitResult preVisitDirectory(FileInfo dir, @NonNull BasicFileAttributes attrs) {
                return results.size() >= max ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
            }

            @Override
            public @NonNull FileVisitResult visitFile(FileInfo file, @NonNull BasicFileAttributes attrs) {
                if (results.size() >= max) {
                    return FileVisitResult.TERMINATE;
                }
                if (!file.isFile()) {
                    return FileVisitResult.CONTINUE;
                }
                if (include != null && !include.isBlank() && !matchGlob(file.getName(), include)) {
                    return FileVisitResult.CONTINUE;
                }

                final String fullPath = file.getPath();
                final String parentPath;
                if (fullPath == null) {
                    return FileVisitResult.CONTINUE;
                }
                int lastSlash = fullPath.lastIndexOf('/');
                parentPath = lastSlash >= 0 ? fullPath.substring(0, Math.max(lastSlash, 1)) : "/";

                try {
                    NetDiskToolUtils.validateTextFile(getFileSystem(), uid, parentPath, file.getName());
                } catch (Exception e) {
                    return FileVisitResult.CONTINUE;
                }

                try {
                    searchInFile(disk, uid, parentPath, file.getName(), regex, results, max);
                } catch (Exception ignored) {
                    // 跳过无法读取的文件
                }

                return results.size() >= max ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
            }

            @Override
            public @NonNull FileVisitResult visitFileFailed(FileInfo file, @NonNull IOException exc) {
                return FileVisitResult.CONTINUE;
            }

            @Override
            public @NonNull FileVisitResult postVisitDirectory(FileInfo dir, IOException exc) {
                return results.size() >= max ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
            }
        });

        return results;
    }

    /**
     * 在单个文件中逐行匹配正则表达式，将匹配结果写入结果列表
     */
    private void searchInFile(String disk, long uid, String parentPath, String name, Pattern pattern,
                              List<TextSearchResult> results, int max) throws IOException {
        Resource resource = getFileSystem().getResource(uid, parentPath, name);
        if (resource == null) {
            return;
        }
        String content = ResourceUtils.resourceToString(resource);
        int lineStart = 0;
        int lineNum = 1;
        for (int i = 0; i < content.length(); i++) {
            if (content.charAt(i) == '\n') {
                String line = content.substring(lineStart, i + 1);
                if (pattern.matcher(line).find()) {
                    results.add(new TextSearchResult(disk, parentPath, name, lineNum, line));
                    if (results.size() >= max) {
                        return;
                    }
                }
                lineStart = i + 1;
                lineNum++;
            }
        }
        if (lineStart < content.length()) {
            String line = content.substring(lineStart);
            if (pattern.matcher(line).find()) {
                results.add(new TextSearchResult(disk, parentPath, name, lineNum, line));
            }
        }
    }

    /**
     * 将简单 glob 模式转换为正则表达式，用于文件名匹配。
     * 支持 {@code *}（匹配任意字符序列）、{@code ?}（匹配单个字符）、
     * {@code {a,b}}（匹配 a 或 b）。
     *
     * @param glob glob 模式字符串
     * @return 对应的正则表达式字符串
     */
    static String globToRegex(String glob) {
        StringBuilder sb = new StringBuilder("^");
        int len = glob.length();
        for (int i = 0; i < len; i++) {
            char c = glob.charAt(i);
            switch (c) {
                case '*' -> sb.append(".*");
                case '?' -> sb.append('.');
                case '{' -> sb.append('(');
                case '}' -> sb.append(')');
                case ',' -> {
                    boolean escaped = i > 0 && glob.charAt(i - 1) == '\\';
                    if (escaped) {
                        sb.append(c);
                    } else {
                        sb.append('|');
                    }
                }
                case '.', '(', ')', '+', '^', '$', '[', ']', '\\' -> sb.append('\\').append(c);
                default -> sb.append(c);
            }
        }
        sb.append('$');
        return sb.toString();
    }

    /**
     * 使用 glob 模式匹配文件名
     *
     * @param name 文件名
     * @param glob glob 模式
     * @return 是否匹配
     */
    static boolean matchGlob(String name, String glob) {
        return name.matches(globToRegex(glob));
    }
}
