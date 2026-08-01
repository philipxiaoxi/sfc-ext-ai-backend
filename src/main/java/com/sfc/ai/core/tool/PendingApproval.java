package com.sfc.ai.core.tool;

import com.xiaotao.saltedfishcloud.model.po.UserPrincipal;
import lombok.AllArgsConstructor;
import lombok.Data;
import org.springframework.ai.tool.ToolCallback;

import java.time.Instant;
import java.util.Map;

/**
 * 待用户批准的敏感工具调用记录。
 */
@Data
@AllArgsConstructor
public class PendingApproval {
    private String toolCallId;
    private String toolName;
    private Map<String, Object> arguments;
    private String purpose;
    private ToolCallback callback;
    private Instant createdAt;

    /** 发起工具调用的用户，批准执行时用于绑定 Spring Security 上下文 */
    private UserPrincipal user;
}
