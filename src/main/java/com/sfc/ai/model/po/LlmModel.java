package com.sfc.ai.model.po;

import com.xiaotao.saltedfishcloud.model.template.AuditModel;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/**
 * AI 模型
 */
@Getter
@Setter
@Entity
@Table(indexes = {
        @Index(name = "idx_llm_model_llm_provider_id", columnList = "llmProviderId")
})
public class LlmModel extends AuditModel {

    /**
     * 关联的提供商 ID
     */
    private Long llmProviderId;

    /**
     * 模型标识
     */
    private String modelId;

    /**
     * 最大上下文长度
     */
    private Integer contextLength;

    /**
     * 思考模式
     */
    private String reasoningEffect;

    /**
     * 是否启用思考模式(预留参数)
     */
    private Boolean enableThinking;
}
