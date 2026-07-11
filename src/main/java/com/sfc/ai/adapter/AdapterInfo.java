package com.sfc.ai.adapter;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

/**
 * 适配器元信息 VO，用于向前端返回系统当前支持的 LLM 适配器信息。
 */
@Getter
@Setter
@AllArgsConstructor
public class AdapterInfo {

    /**
     * 适配器唯一标识，如 "openai"、"deepseek"
     */
    private String id;

    /**
     * 适配器显示名称，如 "OpenAI"、"DeepSeek"
     */
    private String name;

    /**
     * 适配器图标标识。
     * <p>
     * 可以是 Material Design Icon 名称、HTTP/HTTPS 图片 URL 或 base64 图片 Data URL。前端应使用 CommonIcon 渲染。
     */
    private String icon;
}
