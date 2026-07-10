package com.sfc.ai.model.vo;

import com.sfc.ai.model.po.LlmModel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * 模型提供商及其关联模型列表的响应 VO
 */
@Getter
@Setter
@AllArgsConstructor
public class ProviderWithModelsVo {

    /**
     * 提供商信息（不含敏感字段）
     */
    private ProviderVo provider;

    /**
     * 关联的模型列表
     */
    private List<LlmModel> models;
}
