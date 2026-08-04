package com.sfc.ai.model.param;

import com.xiaotao.saltedfishcloud.model.param.PageableRequest;
import lombok.Getter;
import lombok.Setter;

/**
 * 敏感工具调用记录分页查询参数。
 */
@Getter
@Setter
public class SensitiveToolCallQueryParam extends PageableRequest {

    /**
     * 按用户 ID 筛选（可选）
     */
    private Long uid;

    /**
     * 按工具名称模糊筛选（可选）
     */
    private String toolName;

    /**
     * 开始时间（毫秒时间戳，可选）
     */
    private Long startTime;

    /**
     * 结束时间（毫秒时间戳，可选）
     */
    private Long endTime;
}
