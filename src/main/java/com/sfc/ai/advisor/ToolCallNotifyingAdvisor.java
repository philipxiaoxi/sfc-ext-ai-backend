package com.sfc.ai.advisor;

import com.sfc.ai.constant.LlmMessageType;
import com.sfc.ai.model.chat.message.LlmResponse;
import com.sfc.ai.model.chat.payload.ToolCallPayload;
import com.xiaotao.saltedfishcloud.utils.MapperHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionEligibilityChecker;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 扩展 {@link ToolCallingAdvisor}，在每次工具调用执行完成后
 * 向 WebSocket 客户端发送 {@link LlmMessageType#TOOL_CALL} 消息，
 * 告知工具名称、参数和返回值。
 */
@Slf4j
public class ToolCallNotifyingAdvisor extends ToolCallingAdvisor {

    private static final String SESSION_CONTEXT_KEY = "websocketSession";

    public ToolCallNotifyingAdvisor(ToolCallingManager toolCallingManager,
                                     ToolExecutionEligibilityChecker toolExecutionEligibilityChecker,
                                     int advisorOrder,
                                     boolean conversationHistoryEnabled) {
        super(toolCallingManager, toolExecutionEligibilityChecker, advisorOrder, conversationHistoryEnabled);
    }

    @Override
    protected List<Message> doGetNextInstructionsForToolCall(ChatClientRequest chatClientRequest,
                                                              ChatClientResponse chatClientResponse,
                                                              ToolExecutionResult toolExecutionResult) {
        sendToolCallNotification(chatClientRequest, chatClientResponse, toolExecutionResult);
        return super.doGetNextInstructionsForToolCall(chatClientRequest, chatClientResponse, toolExecutionResult);
    }

    @Override
    protected List<Message> doGetNextInstructionsForToolCallStream(ChatClientRequest chatClientRequest,
                                                                    ChatClientResponse chatClientResponse,
                                                                    ToolExecutionResult toolExecutionResult) {
        sendToolCallNotification(chatClientRequest, chatClientResponse, toolExecutionResult);
        return super.doGetNextInstructionsForToolCallStream(chatClientRequest, chatClientResponse, toolExecutionResult);
    }

    @Override
    protected ChatClientRequest doBeforeStream(ChatClientRequest chatClientRequest, StreamAdvisorChain streamAdvisorChain) {
        return super.doBeforeStream(chatClientRequest, streamAdvisorChain);
    }

    @Override
    protected ChatClientRequest doBeforeCall(ChatClientRequest chatClientRequest, CallAdvisorChain callAdvisorChain) {
        return super.doBeforeCall(chatClientRequest, callAdvisorChain);
    }

    @Override
    protected ChatClientResponse doAfterCall(ChatClientResponse chatClientResponse, CallAdvisorChain callAdvisorChain) {
        return super.doAfterCall(chatClientResponse, callAdvisorChain);
    }

    private void sendToolCallNotification(ChatClientRequest request,
                                          ChatClientResponse response,
                                          ToolExecutionResult result) {
        // 这个是 AI 写的，我还在琢磨是怎么个事 直接叫ai解释
        WebSocketSession session = (WebSocketSession) request.context().get(SESSION_CONTEXT_KEY);
        if (session == null || !session.isOpen()) {
            return;
        }

        var chatResponse = response.chatResponse();
        if (chatResponse == null || !chatResponse.hasToolCalls()) {
            return;
        }

        var generation = chatResponse.getResult();
        if (generation == null) {
            return;
        }

        AssistantMessage assistantMessage = generation.getOutput();
        List<AssistantMessage.ToolCall> toolCalls = assistantMessage.getToolCalls();
        if (toolCalls.isEmpty()) {
            return;
        }

        Map<String, String> resultsById = new HashMap<>();
        for (Message msg : result.conversationHistory()) {
            if (msg instanceof ToolResponseMessage trm) {
                for (var tr : trm.getResponses()) {
                    resultsById.put(tr.id(), tr.responseData());
                }
            }
        }

        for (var tc : toolCalls) {
            ToolCallPayload payload = new ToolCallPayload();
            payload.setName(tc.name());
            payload.setArguments(tc.arguments());
            payload.setResult(resultsById.get(tc.id()));

            LlmResponse llmResponse = new LlmResponse();
            llmResponse.setType(LlmMessageType.TOOL_CALL);
            llmResponse.setData(payload);

            try {
                session.sendMessage(new TextMessage(MapperHolder.toJsonNoEx(llmResponse)));
            } catch (IOException e) {
                log.error("发送 TOOL_CALL 消息失败", e);
            }
        }
    }

    /**
     * 创建 {@link ToolCallNotifyingAdvisor} 的构建器。
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * {@link ToolCallNotifyingAdvisor} 的构建器。
     */
    public static class Builder extends ToolCallingAdvisor.Builder<Builder> {

        @Override
        public ToolCallNotifyingAdvisor build() {
            return new ToolCallNotifyingAdvisor(
                    getToolCallingManager(),
                    getToolExecutionEligibilityChecker(),
                    getAdvisorOrder(),
                    isConversationHistoryEnabled()
            );
        }

        @Override
        protected Builder newCopy() {
            return new Builder();
        }
    }
}
