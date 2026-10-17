package com.sfc.ai.tool;

import com.sfc.common.exception.SfcJsonException;
import com.sfc.cloud.remote.client.SfcCoreFileClient;
import com.sfc.cloud.model.SfcFileInfo;
import com.sfc.common.utils.SfcStringUtils;
import feign.Response;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ByteArrayResource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
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
 *
 * <p><b>微服务化改造说明：</b>本类原先在宿主进程内直接读取 {@code DiskFileSystem} 的资源流，改造后改为
 * 「用 {@link SfcCoreFileClient#list} 逐目录远程列举 + 用 {@link SfcCoreFileClient#download} 逐个文件下载内容」。
 * 由于远程逐文件下载的成本远高于进程内读取，这里增加了三重保护，均为原实现所没有的：</p>
 * <ul>
 *   <li><b>单文件上限</b> {@value #MAX_FILE_SIZE} 字节：目录列举结果中明显超过该大小的文件直接跳过；
 *       下载过程中一旦超出该上限也立即中断读取（不会把超大文件读进内存）；</li>
 *   <li><b>扫描文件数上限</b> {@value #MAX_SCANNED_FILES} 个文件：达到上限后停止继续扫描，避免一次搜索长时间占用会话；</li>
 *   <li><b>累计下载字节上限</b> {@value #MAX_TOTAL_SCAN_BYTES} 字节：达到上限后停止继续下载新文件。</li>
 * </ul>
 * <p>流与内存控制：任意时刻<b>只打开一个</b>远程文件的响应流，读完（或跳过）即通过 try-with-resources
 * 依次关闭 {@link InputStream} 与 {@link Response}，绝不并发打开整个网盘的流；单次扫描的峰值内存为
 * 单个文件的内容（≤ {@value #MAX_FILE_SIZE} 字节）。</p>
 */
public class TextSearchTools {

    /** 参与全文扫描的单文件大小上限（4 MiB）：超过则跳过，避免大文件把微服务内存打爆 */
    private static final int MAX_FILE_SIZE = 4 * 1024 * 1024;

    /** 单次全文搜索最多下载扫描的文件数，避免一次工具调用长时间挂起 */
    private static final int MAX_SCANNED_FILES = 500;

    /** 单次全文搜索累计下载的字节上限（64 MiB），达到后停止下载新文件 */
    private static final long MAX_TOTAL_SCAN_BYTES = 64L * 1024 * 1024;

    /** 读取远程响应体时使用的缓冲区大小 */
    private static final int READ_BUFFER_SIZE = 8192;

    /** sfc-core 网盘文件操作统一客户端（远程调用宿主内部接口，替代原先的 DiskFileSystemManager） */
    @Autowired
    private SfcCoreFileClient fileClient;

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
     * <p>远程化说明：遍历改为逐目录调用 {@link SfcCoreFileClient#list} 的广度优先遍历，文件内容改为逐个调用
     * {@link SfcCoreFileClient#download} 获取；起始路径本身就是文件时只扫描该文件（与宿主遍历器一致）。
     * 扫描受 {@value #MAX_SCANNED_FILES} 个文件、{@value #MAX_TOTAL_SCAN_BYTES} 字节与
     * 单文件 {@value #MAX_FILE_SIZE} 字节三重上限约束，无法读取或非纯文本的文件与原实现一样被静默跳过。</p>
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

        // 起始路径可能直接指向一个文件（宿主 walk 的 isFile 分支语义），此时只扫描该文件
        SfcFileInfo singleFile = statFile(uid, path);
        if (singleFile != null) {
            scanFile(disk, uid, parentPathOf(path), singleFile, regex, results, max);
            return results;
        }

        final Deque<String> dirQueue = new ArrayDeque<>();
        dirQueue.add(path);
        int scannedFiles = 0;
        long scannedBytes = 0L;

        while (!dirQueue.isEmpty() && results.size() < max
                && scannedFiles < MAX_SCANNED_FILES && scannedBytes < MAX_TOTAL_SCAN_BYTES) {
            String currentPath = dirQueue.poll();
            for (SfcFileInfo entry : listOrThrow(uid, currentPath)) {
                if (entry.isDir()) {
                    dirQueue.add(SfcStringUtils.appendPath(currentPath, entry.getName()));
                    continue;
                }
                if (results.size() >= max
                        || scannedFiles >= MAX_SCANNED_FILES || scannedBytes >= MAX_TOTAL_SCAN_BYTES) {
                    break;
                }
                if (include != null && !include.isBlank() && !matchGlob(entry.getName(), include)) {
                    continue;
                }
                // 目录列举已给出文件大小，明显过大的文件直接跳过，不必先下载再判断
                Long size = entry.getSize();
                if (size != null && size > MAX_FILE_SIZE) {
                    continue;
                }
                scannedFiles++;
                scannedBytes += scanFile(disk, uid, currentPath, entry, regex, results, max);
            }
        }

        return results;
    }

    /**
     * 下载并扫描单个网盘文件，把命中的行追加到结果列表。
     *
     * <p>读取过程受单文件大小上限约束且完全串行：先通过 {@link SfcCoreFileClient#download} 拿到响应，
     * 用 try-with-resources 依次关闭响应体流与响应本身，再复用宿主时期的纯文本校验与逐行匹配逻辑；
     * 文件不存在、读取失败（如 404/403）或非纯文本文件一律与原实现一样静默跳过。</p>
     *
     * @param disk       网盘类型
     * @param uid        资源所属用户ID
     * @param parentPath 文件所在目录路径
     * @param file       文件信息（来自目录列举，含文件名与大小）
     * @param pattern    正则表达式
     * @param results    匹配结果收集列表
     * @param max        最大返回结果条数
     * @return 本次实际读取的字节数（跳过或读取失败时为 0）
     */
    private long scanFile(String disk, long uid, String parentPath, SfcFileInfo file, Pattern pattern,
                          List<TextSearchResult> results, int max) {
        String name = file.getName();
        String fullPath = SfcStringUtils.appendPath(parentPath, name);
        byte[] bytes;
        try (Response response = fileClient.download(uid, fullPath)) {
            if (response.status() != 200 || response.body() == null) {
                return 0L;
            }
            try (InputStream is = response.body().asInputStream()) {
                bytes = readBounded(is, MAX_FILE_SIZE, fullPath);
            }
            NetDiskToolUtils.validateTextFile(new ByteArrayResource(bytes), fullPath);
        } catch (Exception e) {
            // 与宿主实现一致：跳过无法读取的文件与非纯文本文件
            return 0L;
        }
        searchInContent(disk, parentPath, name, new String(bytes, StandardCharsets.UTF_8), pattern, results, max);
        return bytes.length;
    }

    /**
     * 从远程响应流中读取字节内容，读取过程中超出上限立即中断（不把超大文件读进内存）。
     *
     * @param is        响应体输入流（调用方负责关闭）
     * @param maxSize   允许读取的最大字节数
     * @param fullPath  含文件名本身的完整路径，用于错误提示
     * @return 文件内容
     * @throws IOException      读取响应体失败时抛出
     * @throws SfcJsonException 内容超过上限时抛出
     */
    private byte[] readBounded(InputStream is, int maxSize, String fullPath) throws IOException {
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[READ_BUFFER_SIZE];
            int len;
            while ((len = is.read(buffer)) != -1) {
                if (bos.size() + len > maxSize) {
                    throw new SfcJsonException(400, "文件过大，全文搜索单文件上限为 " + maxSize + " 字节: " + fullPath);
                }
                bos.write(buffer, 0, len);
            }
            return bos.toByteArray();
        }
    }

    /**
     * 在单个文件的文本内容中逐行匹配正则表达式，将匹配结果写入结果列表
     *
     * @param disk       网盘类型
     * @param parentPath 文件所在目录路径
     * @param name       文件名
     * @param content    已读取的文本内容
     * @param pattern    正则表达式
     * @param results    匹配结果收集列表
     * @param max        最大返回结果条数
     */
    private void searchInContent(String disk, String parentPath, String name, String content, Pattern pattern,
                                 List<TextSearchResult> results, int max) {
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
     * 列出网盘目录内容，失败时抛出异常。
     *
     * @param uid  资源所属用户ID
     * @param path 目录路径
     * @return 目录下的文件与目录列表（目录在前）；core 返回空数据时返回空列表
     */
    private List<SfcFileInfo> listOrThrow(long uid, String path) {
        List<SfcFileInfo> entries = fileClient.list(uid, path).getDataOrThrow();
        return entries == null ? Collections.emptyList() : entries;
    }

    /**
     * 判断路径是否指向一个已存在的文件。
     *
     * <p>用于兼容宿主 {@code DiskFileSystemUtils#walk} 的「起始路径本身是文件」语义：
     * 通过列举其父目录并匹配末级节点名判断，父目录列举失败（如路径不存在）时按「不是文件」处理。</p>
     *
     * @param uid  资源所属用户ID
     * @param path 待判断的路径
     * @return 指向已存在文件时返回其文件信息，否则返回 null
     */
    private SfcFileInfo statFile(long uid, String path) {
        if (path == null || path.isEmpty() || path.endsWith("/")) {
            return null;
        }
        String name = lastNodeOf(path);
        if (name.isEmpty()) {
            return null;
        }
        List<SfcFileInfo> entries;
        try {
            entries = listOrThrow(uid, parentPathOf(path));
        } catch (Exception e) {
            return null;
        }
        for (SfcFileInfo entry : entries) {
            if (name.equals(entry.getName())) {
                return entry.isFile() ? entry : null;
            }
        }
        return null;
    }

    /**
     * 取路径的父级路径（与宿主 {@code PathUtils#getParentPath} 语义一致）。
     *
     * @param path 待处理路径
     * @return 父级路径；根路径的父级仍为 {@code "/"}
     */
    private String parentPathOf(String path) {
        String normalized = path;
        if (normalized.endsWith("/")) {
            normalized = normalized.replaceAll("/+$", "");
            if (normalized.isEmpty()) {
                return "/";
            }
        }
        int index = normalized.lastIndexOf('/');
        if (index == 0) {
            return "/";
        }
        if (index == -1) {
            return "";
        }
        return normalized.substring(0, index);
    }

    /**
     * 取路径中最后一个节点的名称（与宿主 {@code PathUtils#getLastNode} 语义一致）。
     *
     * @param path 待处理路径
     * @return 末级节点名；路径为根路径时返回空串
     */
    private String lastNodeOf(String path) {
        String normalized = path.endsWith("/") ? path.replaceAll("/+$", "") : path;
        int index = normalized.lastIndexOf('/');
        return index == -1 ? normalized : normalized.substring(index + 1);
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
