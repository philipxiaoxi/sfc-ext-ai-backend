package com.sfc.ai.service;

import com.sfc.ai.model.param.SensitiveToolCallQueryParam;
import com.sfc.ai.model.po.SensitiveToolCallRecord;
import com.sfc.cloud.model.SfcPageInfo;
import com.sfc.cloud.data.service.SfcCrudService;

/**
 * 敏感工具调用记录服务。
 */
public interface SensitiveToolCallRecordService extends SfcCrudService<SensitiveToolCallRecord> {

    /**
     * 分页查询敏感工具调用记录。
     *
     * @param param 查询参数（支持按 uid、工具名称、时间范围筛选）
     * @return 分页的调用记录
     */
    SfcPageInfo<SensitiveToolCallRecord> listRecords(SensitiveToolCallQueryParam param);
}
