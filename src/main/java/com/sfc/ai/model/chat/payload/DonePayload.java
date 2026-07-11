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

    /**
     * 模型表示
     */
    private String modelId;

    /**
     * 调用耗时（毫秒）
     */
    private Long time;
}
