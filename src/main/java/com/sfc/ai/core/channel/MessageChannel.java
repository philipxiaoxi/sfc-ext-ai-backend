package com.sfc.ai.core.channel;

import com.sfc.ai.constant.LlmMessageType;
import com.sfc.ai.model.chat.message.UserRequest;
import com.xiaotao.saltedfishcloud.model.po.UserPrincipal;

/**
 * AI 聊天消息通道接口，抽象消息的收发传输层。
 * <p>
 * {@link #send} 和 {@link #sendError} 用于 Agent 向远端发送消息；
 * {@link #onMessage} 用于注册入站消息处理器（通常由 AgentExecutor 注册）。
 * 传输层适配器解析入站协议后，直接调用已注册的 {@link MessageHandler#onMessage}。
 */
public interface MessageChannel {

    /**
     * 发送一条指定类型的回复消息。
     *
     * @param type 消息类型
     * @param data 消息数据（Payload 对象）
     */
    void send(LlmMessageType type, Object data);

    /**
     * 发送错误消息。
     *
     * @param message 错误描述
     */
    void sendError(String message);

    /**
     * 注册入站消息处理器。
     *
     * @param handler 入站消息处理器
     */
    void onMessage(MessageHandler handler);

    /** 关闭通道，释放底层资源。 */
    void close();

    /** 获取当前认证用户。 */
    UserPrincipal getUser();

    /** 入站消息处理器。 */
    @FunctionalInterface
    interface MessageHandler {
        void onMessage(UserRequest request);
    }
}
