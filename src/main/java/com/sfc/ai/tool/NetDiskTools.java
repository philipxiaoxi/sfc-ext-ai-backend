package com.sfc.ai.tool;

import com.sfc.ai.annotation.SensitiveTool;
import com.sfc.common.exception.SfcJsonException;
import com.sfc.cloud.remote.client.SfcCoreFileClient;
import com.sfc.cloud.model.SfcFileInfo;
import com.sfc.common.utils.SfcStringUtils;
import feign.Response;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.web.util.UriComponentsBuilder;

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

/**
 * 网盘工具集，提供对公共网盘和私人网盘的常用文件操作功能，
 * 供 AI Agent 调用以管理网盘资源。
 *
 * <p><b>微服务化改造说明：</b>本类原先在宿主进程内直接调用 {@code DiskFileSystem}，改造后统一改为通过
 * {@link SfcCoreFileClient}（OpenFeign）远程回调 sfc-core 的内部接口 {@code /api/internal/file/v2}，
 * 本服务不再持有任何宿主实现类的引用。对外的方法名、{@code @Tool} 声明、入参与返回结构均保持不变。</p>
 *
 * <p><b>与宿主实现的语义差异</b>（远程接口无法覆盖的能力，详见各类方法的说明）：</p>
 * <ul>
 *   <li>读取文本文件改为「先整体下载再切分」，单文件上限 {@value #MAX_TEXT_FILE_SIZE} 字节，
 *       超出上限时抛业务异常，避免大文件把微服务内存打爆；</li>
 *   <li>写入 / 插入 / 替换文本改为「上传覆盖」：远程上传接口只接受文件修改时间（mtime），
 *       无法像宿主那样保留原文件的创建时间（ctime）；</li>
 *   <li>文件列表返回值由宿主的 {@code FileInfo} 变为 {@link SfcFileInfo}（字段略有差异，
 *       例如不再包含 {@code createAt}/{@code updateAt}），递归搜索改为逐目录远程列举。</li>
 * </ul>
 */
public class NetDiskTools {

    /** 文本文件远程读取的最大字节数（4 MiB），超出后直接失败，避免整体下载把微服务内存打爆 */
    private static final int MAX_TEXT_FILE_SIZE = 4 * 1024 * 1024;

    /** 纯文本类型校验只读取文件头部该字节数（与 {@link NetDiskToolUtils#validateTextFile} 的读取长度一致） */
    private static final int TEXT_CHECK_BYTES = 8192;

    /** 读取远程响应体时使用的缓冲区大小 */
    private static final int READ_BUFFER_SIZE = 8192;

    /**
     * 主文件系统协议标识。
     * <p>取值与原宿主常量 {@code ResourceProtocol.MAIN} 逐字一致（{@code "main"}），
     * 用于拼接下载链接中的 {@code protocol} 查询参数。</p>
     */
    private static final String PROTOCOL_MAIN = "main";

    /** 下载链接的接口路径模板（与宿主工具生成的链接保持一致） */
    private static final String DOWNLOAD_LINK_PATH = "/api/resource/{uid}/get";

    /** sfc-core 网盘文件操作统一客户端（远程调用宿主内部接口，替代原先的 DiskFileSystemManager） */
    @Autowired
    private SfcCoreFileClient fileClient;

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
     * <p>远程化说明：原实现依赖宿主的 {@code DiskFileSystemUtils#walk} 进程内遍历，现改为逐目录调用
     * {@link SfcCoreFileClient#list} 的广度优先遍历；起始路径本身就是文件时只处理该文件（与宿主遍历器一致）。</p>
     *
     * @param disk       网盘类型，"public" 或 "private"
     * @param path       搜索的起始目录路径，以字符 '/' 开头和作为分隔符
     * @param regex      文件名正则表达式，如 ".*\\.txt" 匹配所有 txt 文件
     * @param maxResults 最大返回结果条数
     * @return 文件搜索结果列表，包含网盘类型、完整路径、文件大小和文件名
     * @throws IOException 远程列举目录失败时抛出（保持原方法签名）
     */
    @Tool(name = "search_files", description = "在网盘中搜索文件，支持递归子目录和文件名正则匹配")
    public List<FileSearchResult> searchFiles(
            @ToolParam(description = "网盘类型，'public' 表示公共网盘，'private' 表示私人网盘") String disk,
            @ToolParam(description = "搜索的起始目录路径，以 '/' 开头和作为分隔符") String path,
            @ToolParam(description = "文件名正则表达式，如 '.*\\\\.txt' 匹配所有 txt 文件") String regex,
            @ToolParam(description = "最大返回结果条数") Integer maxResults) throws IOException {
        long uid = NetDiskToolUtils.resolveUid(disk);
        Pattern pattern = Pattern.compile(regex);
        List<FileSearchResult> results = new ArrayList<>();
        int max = maxResults != null ? maxResults : Integer.MAX_VALUE;

        // 起始路径可能直接指向一个文件（宿主 walk 的 isFile 分支语义），此时只处理该文件
        SfcFileInfo singleFile = statFile(uid, path);
        if (singleFile != null) {
            if (results.size() < max && pattern.matcher(singleFile.getName()).matches()) {
                results.add(new FileSearchResult(disk, path, singleFile.getSize(), singleFile.getName()));
            }
            return results;
        }

        Deque<String> dirQueue = new ArrayDeque<>();
        dirQueue.add(path);
        while (!dirQueue.isEmpty() && results.size() < max) {
            String currentPath = dirQueue.poll();
            for (SfcFileInfo entry : listOrThrow(uid, currentPath)) {
                if (entry.isDir()) {
                    dirQueue.add(SfcStringUtils.appendPath(currentPath, entry.getName()));
                    continue;
                }
                if (results.size() >= max) {
                    break;
                }
                if (pattern.matcher(entry.getName()).matches()) {
                    results.add(new FileSearchResult(disk, SfcStringUtils.appendPath(currentPath, entry.getName()),
                            entry.getSize(), entry.getName()));
                }
            }
        }

        return results;
    }

    /**
     * 列出指定网盘路径下的文件和目录列表
     *
     * <p>远程化说明：返回类型由宿主的 {@code FileInfo} 变为 {@link SfcFileInfo}；sfc-core 的
     * {@code list} 接口已把目录与文件合并返回（目录在前），与原实现合并
     * {@code getUserFileList} 两个分组的结果一致。</p>
     *
     * @param disk 网盘类型，"public" 或 "private"
     * @param path 目录路径，以字符 '/' 开头和作为分隔符
     * @return 文件和目录的合并列表（目录在前）
     * @throws IOException 远程列举目录失败时抛出（保持原方法签名）
     */
    @Tool(name = "list_files", description = "列出网盘文件列表")
    public List<SfcFileInfo> listFiles(
            @ToolParam(description = "网盘类型，'public' 表示公共网盘，'private' 表示私人网盘") String disk,
            @ToolParam(description = "查询的网盘路径，以字符 '/' 开头和作为分隔符") String path) throws IOException {
        long uid = NetDiskToolUtils.resolveUid(disk);
        return listOrThrow(uid, path);
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
            long uid = NetDiskToolUtils.resolveUid(disk);
            fileClient.rename(uid, path, name, newName).getDataOrThrow();
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
    @SensitiveTool("删除文件 {disk}:{path}/{name}")
    public String delete(
            @ToolParam(description = "网盘类型，'public' 表示公共网盘，'private' 表示私人网盘") String disk,
            @ToolParam(description = "文件或目录所在的父目录路径") String path,
            @ToolParam(description = "要删除的文件名或目录名") String name) {
        try {
            long uid = NetDiskToolUtils.resolveUid(disk);
            fileClient.delete(uid, path, Collections.singletonList(name)).getDataOrThrow();
            return "删除成功";
        } catch (Exception e) {
            return "删除失败: " + e.getMessage();
        }
    }

    /**
     * 移动网盘文件或目录，支持公共网盘与私人网盘之间的双向移动
     *
     * <p>远程化说明：sfc-core 的移动接口需要显式给出「源用户 / 目标用户」，此处由源、目标网盘类型解析得到，
     * 并作为 {@code sourceUid}/{@code targetUid} 传入；接口中的 {@code uid}（当前操作用户）沿用源用户，
     * 真正的权限判定由 core 依据透传的用户凭证对 {@code sourceUid}（需写权限）与 {@code targetUid}
     * （需写权限，公共网盘需管理员）执行。</p>
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
    @SensitiveTool("移动文件 {sourceDisk}:{sourcePath}/{name} -> {targetDisk}:{targetPath}")
    public String move(
            @ToolParam(description = "源网盘类型，'public' 或 'private'") String sourceDisk,
            @ToolParam(description = "源文件或目录所在的父目录路径") String sourcePath,
            @ToolParam(description = "目标网盘类型，'public' 或 'private'") String targetDisk,
            @ToolParam(description = "目标目录路径") String targetPath,
            @ToolParam(description = "要移动的文件名或目录名") String name,
            @ToolParam(description = "是否覆盖目标路径下的同名文件") boolean overwrite) {
        try {
            long sourceUid = NetDiskToolUtils.resolveUid(sourceDisk);
            long targetUid = NetDiskToolUtils.resolveUid(targetDisk);
            fileClient.move(sourceUid, sourceUid, targetUid, sourcePath, targetPath, overwrite,
                    Collections.singletonList(name)).getDataOrThrow();
            return "移动成功";
        } catch (Exception e) {
            return "移动失败: " + e.getMessage();
        }
    }

    /**
     * 复制网盘文件，支持公共网盘与私人网盘之间的双向复制
     *
     * <p>远程化说明：sfc-core 的复制接口要求显式给出文件名列表，因此「不指定 name 即复制源目录下所有内容」
     * 的行为改为先远程列举源目录、再把该目录下的全部条目名作为列表传入一次调用完成，语义与原实现一致。</p>
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
    @SensitiveTool("复制文件 {sourceDisk}:{sourcePath}/{name} -> {targetDisk}:{targetPath}")
    public String copy(
            @ToolParam(description = "源网盘类型，'public' 或 'private'") String sourceDisk,
            @ToolParam(description = "源目录路径") String sourcePath,
            @ToolParam(description = "目标网盘类型，'public' 或 'private'") String targetDisk,
            @ToolParam(description = "目标目录路径") String targetPath,
            @ToolParam(description = "要复制的文件名，不指定则复制源目录下的所有文件", required = false) String name,
            @ToolParam(description = "是否覆盖目标路径下的同名文件") boolean overwrite) {
        try {
            long sourceUid = NetDiskToolUtils.resolveUid(sourceDisk);
            long targetUid = NetDiskToolUtils.resolveUid(targetDisk);
            List<String> names;
            if (name != null && !name.isBlank()) {
                names = Collections.singletonList(name);
            } else {
                names = listOrThrow(sourceUid, sourcePath).stream().map(SfcFileInfo::getName).toList();
                if (names.isEmpty()) {
                    return "复制失败: 源目录下没有可复制的内容";
                }
            }
            fileClient.copy(sourceUid, sourceUid, targetUid, sourcePath, targetPath, overwrite, names).getDataOrThrow();
            return "复制成功";
        } catch (Exception e) {
            return "复制失败: " + e.getMessage();
        }
    }

    /**
     * 递归创建网盘目录（类似 mkdir -p），路径中不存在的父级目录会被一并创建
     *
     * <p>远程化说明：sfc-core 的建目录接口按「父目录路径 + 目录名」传参，
     * 因此这里把入参的完整路径拆分为父路径与末级节点名后调用。</p>
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
            long uid = NetDiskToolUtils.resolveUid(disk);
            String dirName = lastNodeOf(path);
            if (dirName.isEmpty()) {
                return "目录创建失败: 路径中缺少要创建的目录名";
            }
            fileClient.mkdirs(uid, parentPathOf(path), dirName).getDataOrThrow();
            return "目录创建成功";
        } catch (Exception e) {
            return "目录创建失败: " + e.getMessage();
        }
    }

    /**
     * 获取网盘文件的下载链接
     *
     * <p>远程化说明：链接指向的是宿主（sfc-core）对外的统一资源接口，原本仅用于拼装参数值的
     * {@code ResourceRequest} 与 {@code ResourceProtocol.MAIN} 已不再依赖，改为等值常量拼接，链接格式不变。</p>
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
        long uid = NetDiskToolUtils.resolveUid(disk);
        return UriComponentsBuilder.fromPath(DOWNLOAD_LINK_PATH)
                .queryParam("protocol", PROTOCOL_MAIN)
                .queryParam("targetId", String.valueOf(uid))
                .queryParam("path", path)
                .queryParam("name", name)
                .encode()
                .buildAndExpand(uid)
                .toUriString();
    }

    /**
     * 下载网盘文本文件的完整内容，返回按行分割的列表，每项为 [行号, 行内容]
     *
     * @param uid  资源所属用户ID
     * @param path 文件所在目录路径
     * @param name 文件名
     * @return 行号与行内容（含换行符）的二元组列表
     * @throws IllegalArgumentException 文件不存在时抛出
     * @throws SfcJsonException         远程下载失败或文件超过大小上限时抛出
     */
    private List<Object[]> readAllLines(long uid, String path, String name) {
        String fullPath = SfcStringUtils.appendPath(path, name);
        byte[] bytes = downloadFile(uid, fullPath);
        if (bytes == null) {
            throw new IllegalArgumentException("文件不存在: " + fullPath);
        }
        return splitLines(new String(bytes, StandardCharsets.UTF_8));
    }

    /**
     * 把文本内容按 {@code '\n'} 切分为「行号 + 行内容（含换行符）」的列表，语义与原实现逐字一致。
     *
     * @param content 文本内容
     * @return 行号与行内容（含换行符）的二元组列表
     */
    private List<Object[]> splitLines(String content) {
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
     * <p>远程化说明：文件内容改为经 {@link SfcCoreFileClient#download} 整体下载后再切分，
     * 单文件上限 {@value #MAX_TEXT_FILE_SIZE} 字节；下载后先做纯文本类型校验，与原实现顺序一致。</p>
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
        long uid = NetDiskToolUtils.resolveUid(disk);
        String fullPath = SfcStringUtils.appendPath(path, name);

        byte[] bytes = downloadFile(uid, fullPath);
        if (bytes == null) {
            throw new IllegalArgumentException("文件不存在: " + fullPath);
        }
        NetDiskToolUtils.validateTextFile(new ByteArrayResource(bytes), fullPath);

        if (startLine == null || startLine < 1) {
            throw new IllegalArgumentException("startLine 必须大于等于1");
        }
        if (endLine == null || endLine < startLine) {
            throw new IllegalArgumentException("endLine 必须大于等于 startLine");
        }
        if (endLine - startLine + 1 > 1000) {
            throw new IllegalArgumentException("读取行数范围不能超过1000行");
        }

        String content = new String(bytes, StandardCharsets.UTF_8);
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
     * <p>远程化说明：写入改为把文本以内存资源（{@link ByteArrayResource}）上传到 core，由 core 调用与宿主
     * 完全相同的保存链路落盘，因此「同名覆盖 / 父目录自动创建」等行为不变；差异在于远程上传只接受 mtime，
     * 原文件的创建时间（ctime）无法保留。</p>
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
            long uid = NetDiskToolUtils.resolveUid(disk);
            String fullPath = SfcStringUtils.appendPath(path, name);
            NetDiskToolUtils.validateTextFile(headResource(uid, fullPath), fullPath);

            byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
            fileClient.upload(new ByteArrayResource(bytes), uid, path, name, System.currentTimeMillis()).getDataOrThrow();
            return "写入成功";
        } catch (Exception e) {
            return "写入失败: " + e.getMessage();
        }
    }

    /**
     * 在网盘文本文件的指定行号前插入新内容
     *
     * <p>远程化说明：与写入一致，改为「整体下载 → 修改 → 上传覆盖」；
     * 行号语义与原实现完全一致（插入到第 {@code lineNumber} 行之前，总行数+1 表示追加到末尾）。</p>
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
            long uid = NetDiskToolUtils.resolveUid(disk);
            String fullPath = SfcStringUtils.appendPath(path, name);
            byte[] original = downloadFile(uid, fullPath);
            if (original != null) {
                NetDiskToolUtils.validateTextFile(new ByteArrayResource(original), fullPath);
            }
            if (lineNumber == null || lineNumber < 1) {
                return "插入失败: lineNumber 必须大于等于1";
            }
            if (original == null) {
                throw new IllegalArgumentException("文件不存在: " + fullPath);
            }

            List<Object[]> lines = splitLines(new String(original, StandardCharsets.UTF_8));
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
            fileClient.upload(new ByteArrayResource(bytes), uid, path, name, System.currentTimeMillis()).getDataOrThrow();
            return "插入成功";
        } catch (Exception e) {
            return "插入失败: " + e.getMessage();
        }
    }

    /**
     * 替换网盘文本文件指定行号范围内的内容
     *
     * <p>远程化说明：与写入一致，改为「整体下载 → 修改 → 上传覆盖」；行号语义与原实现完全一致。</p>
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
            @ToolParam(description = "替换后的文本内容。注意：替换后不会自动在末尾添加换行符") String content,
            @ToolParam(description = "起始行号（从1开始）") Integer startLine,
            @ToolParam(description = "结束行号（包含）") Integer endLine) {
        try {
            long uid = NetDiskToolUtils.resolveUid(disk);
            String fullPath = SfcStringUtils.appendPath(path, name);
            byte[] original = downloadFile(uid, fullPath);
            if (original != null) {
                NetDiskToolUtils.validateTextFile(new ByteArrayResource(original), fullPath);
            }
            if (startLine == null || startLine < 1) {
                return "替换失败: startLine 必须大于等于1";
            }
            if (endLine == null || endLine < startLine) {
                return "替换失败: endLine 必须大于等于 startLine";
            }
            if (original == null) {
                throw new IllegalArgumentException("文件不存在: " + fullPath);
            }

            List<Object[]> lines = splitLines(new String(original, StandardCharsets.UTF_8));
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
            fileClient.upload(new ByteArrayResource(bytes), uid, path, name, System.currentTimeMillis()).getDataOrThrow();
            return "替换成功";
        } catch (Exception e) {
            return "替换失败: " + e.getMessage();
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
     * 下载网盘文件内容（带大小上限）。
     *
     * @param uid      资源所属用户ID
     * @param fullPath 含文件名本身的完整路径
     * @return 文件字节内容；文件不存在（core 返回 404）时返回 null
     * @throws SfcJsonException 下载失败（非 200/404 状态）或内容超过 {@value #MAX_TEXT_FILE_SIZE} 字节时抛出
     */
    private byte[] downloadFile(long uid, String fullPath) {
        try (Response response = fileClient.download(uid, fullPath)) {
            if (isFileMissing(response, fullPath)) {
                return null;
            }
            return readBytes(response, fullPath, MAX_TEXT_FILE_SIZE);
        } catch (IOException e) {
            throw new SfcJsonException(500, "读取网盘文件失败: " + fullPath + " " + e.getMessage());
        }
    }

    /**
     * 只读取网盘文件头部若干字节，用于纯文本类型校验（避免为了校验把整个文件读进内存）。
     *
     * @param uid      资源所属用户ID
     * @param fullPath 含文件名本身的完整路径
     * @return 文件头部字节；文件不存在时返回 null
     * @throws SfcJsonException 下载失败时抛出
     */
    private ByteArrayResource headResource(long uid, String fullPath) {
        try (Response response = fileClient.download(uid, fullPath)) {
            if (isFileMissing(response, fullPath)) {
                return null;
            }
            if (response.body() == null) {
                return new ByteArrayResource(new byte[0]);
            }
            try (InputStream is = response.body().asInputStream()) {
                return new ByteArrayResource(is.readNBytes(TEXT_CHECK_BYTES));
            }
        } catch (IOException e) {
            throw new SfcJsonException(500, "读取网盘文件失败: " + fullPath + " " + e.getMessage());
        }
    }

    /**
     * 判断下载响应是否表示「文件不存在」：404 视为不存在，其它非 200 状态直接抛业务异常。
     *
     * @param response 下载响应
     * @param fullPath 含文件名本身的完整路径，用于错误提示
     * @return 文件不存在时返回 true
     * @throws SfcJsonException 状态既不是 200 也不是 404 时抛出
     */
    private boolean isFileMissing(Response response, String fullPath) {
        if (response.status() == 404) {
            return true;
        }
        if (response.status() != 200) {
            throw new SfcJsonException(response.status(), "读取网盘文件失败[" + response.status() + "]: " + fullPath);
        }
        return false;
    }

    /**
     * 从下载响应中读取字节内容，读取过程中超出上限立即中断并抛异常。
     *
     * @param response    下载响应（调用方负责关闭）
     * @param fullPath    含文件名本身的完整路径，用于错误提示
     * @param maxSize     允许读取的最大字节数
     * @return 文件内容
     * @throws IOException      读取响应体失败时抛出
     * @throws SfcJsonException 内容超过上限时抛出
     */
    private byte[] readBytes(Response response, String fullPath, int maxSize) throws IOException {
        if (response.body() == null) {
            return new byte[0];
        }
        try (InputStream is = response.body().asInputStream();
             ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[READ_BUFFER_SIZE];
            int len;
            while ((len = is.read(buffer)) != -1) {
                if (bos.size() + len > maxSize) {
                    throw new SfcJsonException(400, "文件过大，远程读取上限为 " + maxSize + " 字节: " + fullPath);
                }
                bos.write(buffer, 0, len);
            }
            return bos.toByteArray();
        }
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
}
