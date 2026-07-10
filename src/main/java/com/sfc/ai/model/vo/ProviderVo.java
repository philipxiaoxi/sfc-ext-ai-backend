package com.sfc.ai.model.vo;

import com.sfc.ai.model.po.LlmProvider;
import lombok.Getter;
import lombok.Setter;

/**
 * 模型提供商响应 VO，仅包含基本信息
 */
@Getter
@Setter
public class ProviderVo {

    /**
     * 提供商 ID
     */
    private Long id;

    /**
     * 提供商名称
     */
    private String name;

    /**
     * 协议类型
     */
    private LlmProvider.ProtocolType protocolType;

    /**
     * 从实体对象创建 VO
     *
     * @param provider 模型提供商实体
     * @return VO 对象
     */
    public static ProviderVo from(LlmProvider provider) {
        ProviderVo vo = new ProviderVo();
        vo.setId(provider.getId());
        vo.setName(provider.getName());
        vo.setProtocolType(provider.getProtocolType());
        return vo;
    }
}
