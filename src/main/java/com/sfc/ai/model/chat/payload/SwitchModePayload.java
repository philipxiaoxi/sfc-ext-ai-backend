package com.sfc.ai.model.chat.payload;

import lombok.Data;

/**
 * SWITCH_MODE 消息的负载数据。
 * <p>
 * 客户端通过此消息切换当前 AI 会话的授权模式。
 */
@Data
public class SwitchModePayload {
    private String mode;
}
