package com.sfc.ai.tool;

import com.xiaotao.saltedfishcloud.constant.UserConstants;
import com.xiaotao.saltedfishcloud.service.file.DiskFileSystem;
import com.xiaotao.saltedfishcloud.utils.SecureUtils;
import com.xiaotao.saltedfishcloud.utils.StringUtils;
import org.springframework.core.io.Resource;

import java.io.IOException;
import java.io.InputStream;

/**
 * 网盘工具方法集合，提供公共/私人网盘的UID解析、文件类型校验等复用逻辑，
 * 供 {@link NetDiskTools} 和 {@link TextSearchTools} 等工具类共享。
 */
public final class NetDiskToolUtils {

    private NetDiskToolUtils() {
    }

    /**
     * 将网盘类型字符串解析为用户 ID
     *
     * @param disk 网盘类型，"public" 表示公共网盘，"private" 表示当前用户的私人网盘
     * @return 对应的用户 ID
     * @throws IllegalArgumentException 当 disk 参数无效或 private 模式下未获取到用户上下文时抛出
     */
    public static long resolveUid(String disk) {
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
     * 校验文件是否为纯文本文件。通过读取文件头部字节检测是否存在 NUL 字节来判断。
     * 若文件不存在则跳过校验（可能为新建文件）。
     *
     * @param fs   文件系统实例
     * @param uid  用户 ID
     * @param path 文件所在目录路径
     * @param name 文件名
     * @throws IllegalArgumentException 当文件不是纯文本文件时抛出
     */
    public static void validateTextFile(DiskFileSystem fs, long uid, String path, String name) {
        Resource resource;
        try {
            resource = fs.getResource(uid, path, name);
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
     *
     * @param data 字节数组
     * @param len  有效字节长度
     * @return 若包含 NUL 字节返回 true，否则返回 false
     */
    public static boolean containsNullByte(byte[] data, int len) {
        for (int i = 0; i < len; i++) {
            if (data[i] == 0x00) {
                return true;
            }
        }
        return false;
    }
}
