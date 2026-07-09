package com.sfc.ai.model.po;

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
     * 协议类型
     */
    @Enumerated(EnumType.STRING)
    private ProtocolType protocolType;

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

    /**
     * 协议类型
     */
    public enum ProtocolType {

        /**
         * OpenAI 协议
         */
        OpenAI,

        /**
         * Anthropic 协议
         */
        Anthropic
    }
}
