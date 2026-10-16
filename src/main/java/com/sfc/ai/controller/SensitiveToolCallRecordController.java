package com.sfc.ai.controller;

import com.sfc.ai.model.param.SensitiveToolCallQueryParam;
import com.sfc.ai.model.po.SensitiveToolCallRecord;
import com.sfc.ai.service.SensitiveToolCallRecordService;
import com.sfc.cloud.constant.SfcSysRole;
import com.sfc.cloud.model.SfcPageInfo;
import com.sfc.cloud.model.json.SfcJsonResult;
import jakarta.annotation.security.RolesAllowed;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 敏感工具调用记录管理控制器（管理员）。
 */
@RestController
@RequestMapping("/api/ai/sensitiveToolCall")
@RequiredArgsConstructor
public class SensitiveToolCallRecordController {

    private final SensitiveToolCallRecordService recordService;

    /**
     * 分页查询敏感工具调用记录。
     *
     * @param param 查询参数（支持按 uid、工具名称、时间范围筛选）
     * @return 分页的调用记录
     */
    @GetMapping("records")
    @RolesAllowed(SfcSysRole.ADMIN)
    public SfcJsonResult<SfcPageInfo<SensitiveToolCallRecord>> listRecords(SensitiveToolCallQueryParam param) {
        return SfcJsonResult.getInstance(recordService.listRecords(param));
    }
}
