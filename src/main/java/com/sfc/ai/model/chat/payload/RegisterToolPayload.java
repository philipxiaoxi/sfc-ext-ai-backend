package com.sfc.ai.model.chat.payload;

import lombok.Data;

/**
 * REGISTER_TOOL 消息的负载数据。
 * <p>
 * 客户端通过此 payload 注册一个新工具的定义，供 LLM 后续调用。
 */
@Data
public class RegisterToolPayload {
    private String name;
    private String description;
    private String parameters;
    /** 是否为敏感操作（需要用户批准） */
    private boolean sensitive;
    /** 用途说明模板，仅在 sensitive=true 时有效，如 "删除 {path}/{name}" */
    private String purposeTemplate;
}
