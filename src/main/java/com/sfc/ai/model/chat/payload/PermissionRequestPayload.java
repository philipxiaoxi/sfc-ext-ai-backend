package com.sfc.ai.model.chat.payload;

import lombok.Data;

import java.util.Map;

/**
 * PERMISSION_REQUEST 消息的负载数据。
 * <p>
 * 当 AI 调用敏感工具且用户处于普通授权模式时，服务器向客户端发送此消息请求用户批准。
 */
@Data
public class PermissionRequestPayload {
    private String toolCallId;
    private String toolName;
    private String purpose;
    private Map<String, Object> arguments;
}
