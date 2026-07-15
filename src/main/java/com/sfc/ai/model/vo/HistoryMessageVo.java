package com.sfc.ai.model.vo;

import lombok.Getter;
import lombok.Setter;

/**
 * 历史消息 VO，可覆盖所有消息角色类型。
 * <p>
 * 不同角色的字段使用情况：
 * <ul>
 *   <li>{@code role = "user"} — {@link #content}</li>
 *   <li>{@code role = "ai"} — {@link #content}、{@link #reasoningContent}</li>
 *   <li>{@code role = "tool"} — {@link #id}、{@link #name}、{@link #arguments}、{@link #result}、{@link #status}</li>
 * </ul>
 * 直接映射前端 {@code ChatMessage} 联合类型。
 */
@Getter
@Setter
public class HistoryMessageVo {

    /** 角色：user / ai / tool / done */
    private String role;

    /** 消息文本内容（user / ai） */
    private String content;

    /** 推理思考内容（ai） */
    private String reasoningContent;

    /** 工具调用 ID（tool） */
    private String id;

    /** 工具名称（tool） */
    private String name;

    /** 工具参数 JSON 字符串（tool） */
    private String arguments;

    /** 工具执行结果（tool） */
    private String result;

    /** 工具调用状态：pending / done（tool） */
    private String status;

    public static HistoryMessageVo user(String content) {
        HistoryMessageVo vo = new HistoryMessageVo();
        vo.setRole("user");
        vo.setContent(content);
        return vo;
    }

    public static HistoryMessageVo ai(String content, String reasoningContent) {
        HistoryMessageVo vo = new HistoryMessageVo();
        vo.setRole("ai");
        vo.setContent(content);
        vo.setReasoningContent(reasoningContent);
        return vo;
    }

    public static HistoryMessageVo tool(String id, String name, String arguments, String status) {
        HistoryMessageVo vo = new HistoryMessageVo();
        vo.setRole("tool");
        vo.setId(id);
        vo.setName(name);
        vo.setArguments(arguments);
        vo.setStatus(status);
        return vo;
    }
}
