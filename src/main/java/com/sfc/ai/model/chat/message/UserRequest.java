package com.sfc.ai.model.chat.message;

import com.sfc.ai.constant.UserMessageType;
import lombok.Data;

@Data
public class UserRequest {
    private UserMessageType type;
    private String message;
}
