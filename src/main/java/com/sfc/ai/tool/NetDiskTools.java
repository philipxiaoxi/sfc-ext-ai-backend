package com.sfc.ai.tool;

import com.xiaotao.saltedfishcloud.constant.ResourceProtocol;
import com.xiaotao.saltedfishcloud.constant.UserConstants;
import com.xiaotao.saltedfishcloud.model.dto.ResourceRequest;
import com.xiaotao.saltedfishcloud.model.po.file.FileInfo;
import com.xiaotao.saltedfishcloud.model.param.SimpleFileTransferParam;
import com.xiaotao.saltedfishcloud.service.file.DiskFileSystem;
import com.xiaotao.saltedfishcloud.service.file.DiskFileSystemManager;
import com.xiaotao.saltedfishcloud.utils.SecureUtils;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
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
     * 列出指定网盘路径下的文件和目录列表
     *
     * @param disk 网盘类型，"public" 或 "private"
     * @param path 目录路径，以字符 '/' 开头和作为分隔符
     * @return 文件和目录的合并列表
     */
    @Tool(description = "列出网盘文件列表")
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
    @Tool(description = "重命名网盘文件或目录")
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
    @Tool(description = "删除网盘文件或目录。目录下存在子文件或目录时也能直接删除。对于挂载点则是直接移除挂载点。")
    public String delete(
            @ToolParam(description = "网盘类型，'public' 表示公共网盘，'private' 表示私人网盘") String disk,
            @ToolParam(description = "文件或目录所在的父目录路径") String path,
            @ToolParam(description = "要删除的文件名或目录名") String name) {
        try {
            long uid = resolveUid(disk);
            long count = getFileSystem().deleteFile(uid, path, Collections.singletonList(name));
            return "删除成功，共删除 " + count + " 个文件/目录";
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
    @Tool(description = "移动网盘文件或目录，支持公共网盘与私人网盘之间的双向移动")
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
    @Tool(description = "复制网盘文件，支持公共网盘与私人网盘之间的双向复制")
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
     * 获取网盘文件的下载链接
     *
     * @param disk 网盘类型，"public" 表示公共网盘，"private" 表示私人网盘
     * @param path 文件所在的目录路径，以字符 '/' 开头和作为分隔符
     * @param name 文件名
     * @return 下载链接（以 / 开头）
     */
    @Tool(description = "获取网盘文件的下载链接，返回以 '/' 开头的路径")
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
}
