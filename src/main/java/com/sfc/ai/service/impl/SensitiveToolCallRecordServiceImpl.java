package com.sfc.ai.service.impl;

import com.sfc.ai.model.param.SensitiveToolCallQueryParam;
import com.sfc.ai.model.po.SensitiveToolCallRecord;
import com.sfc.ai.repo.SensitiveToolCallRecordRepo;
import com.sfc.ai.service.SensitiveToolCallRecordService;
import com.xiaotao.saltedfishcloud.model.CommonPageInfo;
import com.xiaotao.saltedfishcloud.service.CrudServiceImpl;
import com.xiaotao.saltedfishcloud.utils.db.JpaLambdaQueryWrapper;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

/**
 * 敏感工具调用记录服务实现。
 */
public class SensitiveToolCallRecordServiceImpl extends CrudServiceImpl<SensitiveToolCallRecord, SensitiveToolCallRecordRepo>
        implements SensitiveToolCallRecordService {

    /**
     * 默认分页大小
     */
    private static final int DEFAULT_PAGE_SIZE = 20;

    @Override
    public CommonPageInfo<SensitiveToolCallRecord> listRecords(SensitiveToolCallQueryParam param) {
        int page = param.getPage() != null ? param.getPage() : 0;
        int size = param.getSize() != null ? param.getSize() : DEFAULT_PAGE_SIZE;
        JpaLambdaQueryWrapper<SensitiveToolCallRecord> wrapper = JpaLambdaQueryWrapper.get(SensitiveToolCallRecord.class)
                .eq(SensitiveToolCallRecord::getUid, param.getUid())
                .like(SensitiveToolCallRecord::getToolName, param.getToolName())
                .ge(SensitiveToolCallRecord::getCreateAt, param.getStartTime())
                .le(SensitiveToolCallRecord::getCreateAt, param.getEndTime());
        Page<SensitiveToolCallRecord> records = repository.findAll(wrapper.build(),
                PageRequest.of(page, size, Sort.Direction.DESC, "id"));
        return CommonPageInfo.of(records);
    }
}
