package com.sfc.ai.model.chat.payload;

import lombok.Data;

/**
 * APPROVE 消息的负载数据。
 * <p>
 * 客户端对 PERMISSION_REQUEST 的响应，表示批准或拒绝本次工具调用。
 */
@Data
public class ApprovePayload {
    private String toolCallId;
    private boolean approved;
}
