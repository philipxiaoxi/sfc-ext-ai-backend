package com.sfc.ai.core.tool;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.resolution.ToolCallbackResolver;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

/**
 * 工具回调解析器，当 LLM 调用一个不存在的工具时，返回一个回退的工具回调，
 * 该回调向 LLM 返回错误提示信息，避免因未知工具名导致 Agent 执行流程中断。
 */
public class FallbackToolCallbackResolver implements ToolCallbackResolver {

    private static final String FALLBACK_INPUT_SCHEMA = "{\"type\":\"object\",\"properties\":{},\"required\":[]}";

    @Override
    public @Nullable ToolCallback resolve(@NonNull String toolName) {
        return new FallbackToolCallback(toolName);
    }

    private record FallbackToolCallback(ToolDefinition toolDefinition) implements ToolCallback {
            private FallbackToolCallback(String toolDefinition) {
                this(ToolDefinition.builder()
                        .name(toolDefinition)
                        .description("Fallback tool for unknown tool: " + toolDefinition)
                        .inputSchema(FALLBACK_INPUT_SCHEMA)
                        .build());
            }

            @Override
            public @NonNull ToolDefinition getToolDefinition() {
                return toolDefinition;
            }

            @Override
            public @NonNull ToolMetadata getToolMetadata() {
                return ToolMetadata.builder().build();
            }

            @Override
            public @NonNull String call(@NonNull String toolInput) {
                return "错误：工具 '" + toolDefinition.name() + "' 不存在。请检查工具名称是否正确，仅可使用已注册的工具。";
            }
        }
}
