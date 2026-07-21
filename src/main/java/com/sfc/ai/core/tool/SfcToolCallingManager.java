package com.sfc.ai.core.tool;

import com.sfc.ai.constant.LlmMessageType;
import com.sfc.ai.core.ToolExecutionManager;
import com.sfc.ai.core.channel.MessageChannel;
import com.sfc.ai.model.chat.payload.ToolCallStartPayload;
import com.xiaotao.saltedfishcloud.model.po.UserPrincipal;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.execution.DefaultToolExecutionExceptionProcessor;
import org.springframework.ai.tool.execution.ToolExecutionException;
import org.springframework.ai.tool.execution.ToolExecutionExceptionProcessor;
import org.springframework.ai.tool.resolution.ToolCallbackResolver;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 咸鱼云自定义的 {@link ToolCallingManager} 实现，在执行工具调用循环的同时
 * 统一处理工具调用的生命周期通知：
 * <ul>
 *   <li>在执行任何工具<b>之前</b>，一次性向客户端发送当前响应中所有工具调用的
 *       {@link LlmMessageType#TOOL_CALL_START} 通知，使用 LLM 返回的 {@code toolCall.id()}
 *       作为客户端关联 ID</li>
 *   <li>每个工具执行完成后立即通过 {@link ToolExecutionManager#executeToolCall}
 *       发送 {@link LlmMessageType#TOOL_CALL_END}(SUCCESS / ERROR)</li>
 * </ul>
 * <p>
 * 替代了原 {@code ToolCallNotificationAdvisor}（发送 START）与
 * {@code SfcAgentToolCallbackDecorator}（执行 + 发送 END）的组合，
 * 消除了两者之间用于传递 ID 的 FIFO 队列（{@code ToolCallIdProvider}）。
 * <p>
 * 本实现不使用 Spring AI 的工具调用 Observation 体系（项目未启用 Observation）。
 * 工具异常处理委托给 {@link DefaultToolExecutionExceptionProcessor}（默认行为：将异常
 * 转换为错误消息字符串回传给 LLM）。
 *
 * @see ToolExecutionManager#executeToolCall
 */
@Slf4j
public class SfcToolCallingManager implements ToolCallingManager {

    private static final Log logger = LogFactory.getLog(SfcToolCallingManager.class);

    private static final String POSSIBLE_LLM_TOOL_NAME_CHANGE_WARNING_START = "LLM may have adapted the tool name '";
    private static final String POSSIBLE_LLM_TOOL_NAME_CHANGE_WARNING_END
            = "', especially if the name was truncated due to length limits. If this is the case, you can customize the prefixing and processing logic using McpToolNamePrefixGenerator";

    private final ToolCallbackResolver toolCallbackResolver;

    private final ToolExecutionExceptionProcessor toolExecutionExceptionProcessor;

    private final ToolExecutionManager executionManager;

    private final MessageChannel channel;

    private final UserPrincipal user;

    /**
     * 构造工具调用管理器。
     *
     * @param toolCallbackResolver           工具回调解析器，当 ChatOptions 中的工具回调
     *                                       按 name 找不到时使用的回退解析器
     * @param toolExecutionExceptionProcessor 工具执行异常处理器，将异常转换为回传 LLM 的字符串
     * @param executionManager               工具执行管理器，负责实际执行单次工具调用并发送 END
     * @param channel                        消息通道，用于发送 TOOL_CALL_START
     * @param user                           当前用户，用于绑定 Spring Security 上下文；可为 null
     */
    public SfcToolCallingManager(ToolCallbackResolver toolCallbackResolver,
                                 ToolExecutionExceptionProcessor toolExecutionExceptionProcessor,
                                 ToolExecutionManager executionManager,
                                 MessageChannel channel,
                                 @Nullable UserPrincipal user) {
        this.toolCallbackResolver = toolCallbackResolver;
        this.toolExecutionExceptionProcessor = toolExecutionExceptionProcessor;
        this.executionManager = executionManager;
        this.channel = channel;
        this.user = user;
    }

    @Override
    public @NonNull List<ToolDefinition> resolveToolDefinitions(ToolCallingChatOptions chatOptions) {
        List<ToolCallback> toolCallbacks = new ArrayList<>(
                !CollectionUtils.isEmpty(chatOptions.getToolCallbacks()) ? chatOptions.getToolCallbacks() : List.of());
        return toolCallbacks.stream().map(ToolCallback::getToolDefinition).toList();
    }

    @Override
    public @NonNull ToolExecutionResult executeToolCalls(@NonNull Prompt prompt, @NonNull ChatResponse chatResponse) {
        Optional<Generation> toolCallGeneration = chatResponse.getResults()
                .stream()
                .filter(g -> !CollectionUtils.isEmpty(g.getOutput().getToolCalls()))
                .findFirst();

        if (toolCallGeneration.isEmpty()) {
            throw new IllegalStateException("No tool call requested by the chat model");
        }

        AssistantMessage assistantMessage = toolCallGeneration.get().getOutput();

        List<AssistantMessage.ToolCall> toolCalls = assistantMessage.getToolCalls();

        // 一次性发送所有 TOOL_CALL_START 通知，使用 LLM 返回的 toolCall.id() 作为客户端关联 ID
        notifyToolCallStarts(toolCalls);

        ToolContext toolContext = buildToolContext(prompt);
        List<ToolCallback> toolCallbacks = resolveToolCallbacks(prompt);

        List<ToolResponseMessage.ToolResponse> toolResponses = new ArrayList<>();
        Boolean returnDirect = null;

        for (AssistantMessage.ToolCall toolCall : toolCalls) {
            String toolName = toolCall.name();
            String toolInputArguments = toolCall.arguments();

            // 处理流式模式下参数可能为 null 的情况
            final String finalToolInputArguments;
            if (!StringUtils.hasText(toolInputArguments)) {
                logger.warn("Tool call arguments are null or empty for tool: " + toolName
                        + ". Using empty JSON object as default.");
                finalToolInputArguments = "{}";
            } else {
                finalToolInputArguments = toolInputArguments;
            }

            ToolCallback toolCallback = toolCallbacks.stream()
                    .filter(tool -> toolName.equals(tool.getToolDefinition().name()))
                    .findFirst()
                    .orElseGet(() -> this.toolCallbackResolver.resolve(toolName));

            if (toolCallback == null) {
                logger.warn(POSSIBLE_LLM_TOOL_NAME_CHANGE_WARNING_START + toolName
                        + POSSIBLE_LLM_TOOL_NAME_CHANGE_WARNING_END);
                throw new IllegalStateException("No ToolCallback found for tool name: " + toolName);
            }

            if (returnDirect == null) {
                returnDirect = toolCallback.getToolMetadata().returnDirect();
            } else {
                returnDirect = returnDirect && toolCallback.getToolMetadata().returnDirect();
            }

            String toolCallResult = executeSingleToolCall(toolCallback, toolCall.id(), toolName,
                    finalToolInputArguments, toolContext);

            toolResponses.add(new ToolResponseMessage.ToolResponse(toolCall.id(), toolName,
                    toolCallResult != null ? toolCallResult : ""));
        }

        List<Message> conversationHistory = buildConversationHistoryAfterToolExecution(
                prompt.getInstructions(), assistantMessage,
                ToolResponseMessage.builder().responses(toolResponses).build());

        return ToolExecutionResult.builder()
                .conversationHistory(conversationHistory)
                .returnDirect(Objects.requireNonNullElse(returnDirect, false))
                .build();
    }

    /**
     * 一次性发送当前响应中所有工具调用的 {@link LlmMessageType#TOOL_CALL_START} 通知，
     * 并将每个工具调用注册到 {@link ToolExecutionManager#announceToolCall}。
     * <p>
     * 在任何工具执行之前调用，确保：
     * <ul>
     *   <li>客户端第一时间收到全部工具调用开始通知</li>
     *   <li>STOP 中断时即使工具尚未进入执行也能收到 CANCELLED 通知</li>
     * </ul>
     *
     * @param toolCalls LLM 响应中的工具调用列表
     */
    private void notifyToolCallStarts(List<AssistantMessage.ToolCall> toolCalls) {
        for (AssistantMessage.ToolCall toolCall : toolCalls) {
            executionManager.announceToolCall(toolCall.id(), toolCall.name());
            ToolCallStartPayload payload = new ToolCallStartPayload();
            payload.setId(toolCall.id());
            payload.setName(toolCall.name());
            payload.setArguments(toolCall.arguments());
            channel.send(LlmMessageType.TOOL_CALL_START, payload);
            log.debug("工具调用开始 id: {} name: {}", toolCall.id(), toolCall.name());
        }
    }

    /**
     * 从 Prompt 的 ChatOptions 中构建 {@link ToolContext}。
     *
     * @param prompt 当前请求的 Prompt
     * @return 工具上下文
     */
    private static ToolContext buildToolContext(Prompt prompt) {
        Map<String, Object> toolContextMap = Map.of();
        if (prompt.getOptions() instanceof ToolCallingChatOptions toolCallingChatOptions
                && !CollectionUtils.isEmpty(toolCallingChatOptions.getToolContext())) {
            toolContextMap = new HashMap<>(toolCallingChatOptions.getToolContext());
        }
        return new ToolContext(toolContextMap);
    }

    /**
     * 从 Prompt 的 ChatOptions 中获取已注册的工具回调列表。
     *
     * @param prompt 当前请求的 Prompt
     * @return 工具回调列表，可能为空
     */
    private static List<ToolCallback> resolveToolCallbacks(Prompt prompt) {
        List<ToolCallback> toolCallbacks = List.of();
        if (prompt.getOptions() instanceof ToolCallingChatOptions toolCallingChatOptions
                && !CollectionUtils.isEmpty(toolCallingChatOptions.getToolCallbacks())) {
            toolCallbacks = toolCallingChatOptions.getToolCallbacks();
        }
        return toolCallbacks;
    }

    /**
     * 执行单次工具调用，捕获 {@link ToolExecutionException} 并通过
     * {@link ToolExecutionExceptionProcessor} 转换为回传 LLM 的字符串。
     * {@link LlmMessageType#TOOL_CALL_END}(SUCCESS / ERROR) 由
     * {@link ToolExecutionManager#executeToolCall} 内部发送。
     *
     * @param toolCallback           被调用的工具回调
     * @param toolCallId             工具调用 ID
     * @param toolName               工具名称
     * @param finalToolInputArguments 工具输入参数（JSON 字符串，非空）
     * @param toolContext            工具上下文
     * @return 工具执行结果字符串
     */
    private String executeSingleToolCall(ToolCallback toolCallback, String toolCallId, String toolName,
                                          String finalToolInputArguments, ToolContext toolContext) {
        try {
            return executionManager.executeToolCall(toolCallback, toolCallId, toolName,
                    finalToolInputArguments, toolContext, user, channel);
        } catch (ToolExecutionException ex) {
            return toolExecutionExceptionProcessor.process(ex);
        }
    }

    /**
     * 工具执行完成后构建对话历史：在原有消息之后追加 AssistantMessage 与 ToolResponseMessage。
     *
     * @param previousMessages    原有消息列表
     * @param assistantMessage    LLM 响应的 AssistantMessage（含 tool_calls）
     * @param toolResponseMessage 工具执行结果消息
     * @return 更新后的对话历史
     */
    private static List<Message> buildConversationHistoryAfterToolExecution(List<Message> previousMessages,
                                                                             AssistantMessage assistantMessage,
                                                                             ToolResponseMessage toolResponseMessage) {
        List<Message> messages = new ArrayList<>(previousMessages);
        messages.add(assistantMessage);
        messages.add(toolResponseMessage);
        return messages;
    }
}
