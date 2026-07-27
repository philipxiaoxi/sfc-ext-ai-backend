package com.sfc.ai.core.tool;

import com.sfc.ai.annotation.SensitiveTool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 敏感工具注册表。
 * <p>
 * 统一管理所有敏感工具（内建 + 客户端注册）的元信息，提供敏感判断和用途说明模板渲染能力。
 */
@Slf4j
public class SensitiveToolRegistry {

    private final Map<String, String> sensitiveTools = new ConcurrentHashMap<>();

    /**
     * 注册一个敏感工具。
     *
     * @param toolName         工具名称
     * @param purposeTemplate  用途说明模板，如 "删除文件 {disk}:{path}/{name}"
     */
    public void register(String toolName, String purposeTemplate) {
        sensitiveTools.put(toolName, purposeTemplate);
        log.debug("注册敏感工具: {} -> {}", toolName, purposeTemplate);
    }

    /**
     * 扫描对象的所有方法，注册其中带 {@code @SensitiveTool} 注解的工具。
     *
     * @param bean 工具 Bean 实例
     */
    public void scanBean(Object bean) {
        for (Method method : bean.getClass().getDeclaredMethods()) {
            SensitiveTool annotation = method.getAnnotation(SensitiveTool.class);
            if (annotation != null) {
                Tool toolAnnotation = method.getAnnotation(Tool.class);
                String toolName = toolAnnotation != null && !toolAnnotation.name().isEmpty()
                        ? toolAnnotation.name()
                        : method.getName();
                register(toolName, annotation.value());
            }
        }
    }

    /**
     * 判断指定工具是否为敏感工具。
     */
    public boolean isSensitive(String toolName) {
        return sensitiveTools.containsKey(toolName);
    }

    /**
     * 渲染用途说明：将模板中的 {paramName} 替换为实际参数值。
     *
     * @param toolName  工具名称
     * @param arguments 工具参数
     * @return 渲染后的用途说明字符串
     */
    public String resolvePurpose(String toolName, Map<String, Object> arguments) {
        String template = sensitiveTools.get(toolName);
        if (template == null) {
            return toolName;
        }
        Pattern pattern = Pattern.compile("\\{(\\w+)}");
        Matcher matcher = pattern.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String paramName = matcher.group(1);
            String value = arguments != null && arguments.containsKey(paramName)
                    ? String.valueOf(arguments.get(paramName))
                    : "?" + paramName;
            matcher.appendReplacement(sb, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }
}
