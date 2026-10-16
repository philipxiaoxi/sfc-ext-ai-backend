package com.sfc.ai.model.po;

import com.sfc.ai.constant.SensitiveToolCallStatus;
import com.sfc.cloud.data.model.SfcAuditModel;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * 敏感工具调用记录实体。
 * <p>
 * 记录每次由 LLM 发起的敏感工具调用的执行情况（自动执行 / 批准执行 / 拒绝），
 * 供管理员审计查询。
 */
@Getter
@Setter
@Entity
@Table(indexes = {
        @Index(name = "idx_sensitive_tool_call_record_uid_create_at", columnList = "uid,createAt")
})
public class SensitiveToolCallRecord extends SfcAuditModel {

    /**
     * 发起调用的用户名（冗余存储，便于审计展示）
     */
    private String username;

    /**
     * 发起调用的 AI 会话 ID
     */
    private String conversationId;

    /**
     * 敏感工具名称
     */
    private String toolName;

    /**
     * 工具用途说明
     */
    private String purpose;

    /**
     * 工具调用参数（JSON 格式）
     */
    @Lob
    private String arguments;

    /**
     * 调用状态（自动执行 / 批准执行 / 拒绝）
     */
    @Enumerated(EnumType.STRING)
    private SensitiveToolCallStatus status;

    /**
     * 是否执行成功（被拒绝的记录恒为 false）
     */
    private Boolean success;

    /**
     * 工具执行结果或错误信息
     */
    @Lob
    private String result;
}
