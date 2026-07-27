package com.sfc.ai.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记 AI 工具方法为敏感操作，执行前需要用户批准。
 * <p>
 * 适用于标注在 Spring AI {@code @Tool} 方法上，用于指定该工具为危险操作。
 * 当用户未处于"完全授权"模式时，调用此工具会先向客户端发送 PERMISSION_REQUEST，
 * 等待用户批准后再执行。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface SensitiveTool {

    /**
     * 用途说明模板，支持 {paramName} 占位符引用工具参数。
     * 例如："删除文件 {disk}:{path}/{name}"
     */
    String value();
}
