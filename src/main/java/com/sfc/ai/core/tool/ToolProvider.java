package com.sfc.ai.core.tool;

import com.sfc.ai.tool.CommonTools;
import com.sfc.ai.tool.NetDiskTools;
import com.sfc.ai.tool.TextSearchTools;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;

import java.util.Arrays;
import java.util.List;

/**
 * 内建 AI 工具集门面。
 * <p>
 * 将 {@link CommonTools}、{@link NetDiskTools}、{@link TextSearchTools}
 * 三个 Spring Bean 聚合为统一的 {@link ToolCallback} 列表，
 * 供 {@code ChatRunner} 注入使用。
 */
public class ToolProvider {

    private final List<ToolCallback> toolCallbacks;

    public ToolProvider(CommonTools commonTools,
                        NetDiskTools netDiskTools,
                        TextSearchTools textSearchTools) {
        this.toolCallbacks = Arrays.asList(ToolCallbacks.from(commonTools, netDiskTools, textSearchTools));
    }

    /**
     * 获取所有内建工具回调列表。
     */
    public List<ToolCallback> allToolCallbacks() {
        return toolCallbacks;
    }
}
