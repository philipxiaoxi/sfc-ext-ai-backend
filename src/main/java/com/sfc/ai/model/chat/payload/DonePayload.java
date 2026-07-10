package com.sfc.ai.model.chat.payload;

import lombok.Data;

/**
 * DONE 消息类型的专属数据。
 */
@Data
public class DonePayload {
    /**
     * 停止原因
     */
    private String reason;
}
