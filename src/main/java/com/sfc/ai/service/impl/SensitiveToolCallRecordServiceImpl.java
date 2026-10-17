package com.sfc.ai.service.impl;

import com.sfc.ai.model.param.SensitiveToolCallQueryParam;
import com.sfc.ai.model.po.SensitiveToolCallRecord;
import com.sfc.ai.repo.SensitiveToolCallRecordRepo;
import com.sfc.ai.service.SensitiveToolCallRecordService;
import com.sfc.ai.utils.SfcLambdaSpecification;
import com.sfc.common.model.SfcPageInfo;
import com.sfc.cloud.data.service.SfcCrudServiceImpl;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

/**
 * 敏感工具调用记录服务实现。
 */
public class SensitiveToolCallRecordServiceImpl extends SfcCrudServiceImpl<SensitiveToolCallRecord, SensitiveToolCallRecordRepo>
        implements SensitiveToolCallRecordService {

    /**
     * 默认分页大小
     */
    private static final int DEFAULT_PAGE_SIZE = 20;

    @Override
    public SfcPageInfo<SensitiveToolCallRecord> listRecords(SensitiveToolCallQueryParam param) {
        int page = param.getPage() != null ? param.getPage() : 0;
        int size = param.getSize() != null ? param.getSize() : DEFAULT_PAGE_SIZE;
        // 动态条件：值为 null 的筛选项自动忽略（等价于宿主 JpaLambdaQueryWrapper 的默认 NullStrategy.IGNORE）；
        // 时间范围取自毫秒时间戳，比较时按属性类型（Date）自动换算，见 SfcLambdaSpecification
        Specification<SensitiveToolCallRecord> specification = SfcLambdaSpecification.get(SensitiveToolCallRecord.class)
                .eq(SensitiveToolCallRecord::getUid, param.getUid())
                .like(SensitiveToolCallRecord::getToolName, param.getToolName())
                .ge(SensitiveToolCallRecord::getCreateAt, param.getStartTime())
                .le(SensitiveToolCallRecord::getCreateAt, param.getEndTime())
                .build();
        Page<SensitiveToolCallRecord> records = repository.findAll(specification,
                PageRequest.of(page, size, Sort.Direction.DESC, "id"));
        return SfcPageInfo.of(records);
    }
}
