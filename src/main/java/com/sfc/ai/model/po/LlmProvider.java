package com.sfc.ai.model.po;

import com.sfc.ai.core.adapter.LlmChatAdapter;
import com.xiaotao.saltedfishcloud.model.template.AuditModel;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/**
 * AI 模型提供商
 */
@Getter
@Setter
@Entity
@Table(indexes = {
        @Index(name = "idx_llm_provider_name", columnList = "name")
})
public class LlmProvider extends AuditModel {

    /**
     * 提供商名称
     */
    private String name;

    /**
     * 适配器标识，如 "openai"、"deepseek"。
     * 对应 {@link LlmChatAdapter#getId()} 返回的值。
     */
    private String adapter;

    /**
     * 请求地址
     */
    private String baseUrl;

    /**
     * API 密钥
     */
    private String apiKey;

    /**
     * 模型列表请求地址
     */
    private String modelListUrl;

    /**
     * 自定义请求头（JSON 格式 key: value）
     */
    @Lob
    private String customHeader;

}
