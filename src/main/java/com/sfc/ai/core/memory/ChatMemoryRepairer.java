package com.sfc.ai.core.memory;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 聊天记忆修复组件。
 * <p>
 * 当 LLM 响应流或工具调用被 STOP / 连接关闭中断时，ChatMemory 中可能残留
 * "带 toolCalls 的 assistant 消息缺少对应 tool 结果消息"的悬空序列。
 * 该序列在后续请求中会触发 LLM API 的 400 校验错误
 * （带 tool_calls 的 assistant 消息之后必须紧跟每个 tool_call_id 对应的 role=tool 消息）。
 * <p>
 * 本类提供两类能力：
 * <ul>
 *   <li>实例方法 {@link #compensate(String)}：从持久层读取会话消息并写回补偿消息，
 *       供 STOP / 连接关闭中断场景使用</li>
 *   <li>静态方法 {@link #repairInMemory(List)} 与 {@link #buildTailCompensation(List)}：
 *       纯内存级的修复与补偿构造，便于单元测试与就地调用</li>
 * </ul>
 */
@Slf4j
@RequiredArgsConstructor
public class ChatMemoryRepairer {

    /**
     * 聊天记忆持久化仓库，用于读取会话消息与写回补偿工具结果消息。
     */
    private final JpaChatMemoryRepository chatMemoryRepository;

    /** 被中断的工具调用的占位结果文本，作为合成 tool 结果消息的内容参与后续 LLM 上下文 */
    public static final String CANCELLED_TOOL_RESULT = "工具调用已被用户中断";

    /**
     * 对消息序列做内存级完整性修复（不传入额外已覆盖 ID）。
     *
     * @param messages 原始消息序列（不会被修改）
     * @return 修复后的消息序列；无悬空 toolCalls 时返回原序列
     * @see #repairInMemory(List, Set)
     */
    public static List<Message> repairInMemory(List<Message> messages) {
        return repairInMemory(messages, Set.of());
    }

    /**
     * 对消息序列做内存级完整性修复。
     * <p>
     * 扫描每条带 toolCalls 的 assistant 消息，若其后续的 tool 结果消息未能覆盖全部
     * toolCallId，则在该 assistant 消息之后插入合成的 {@link ToolResponseMessage}，
     * 为每个缺失的 toolCallId 生成内容为 {@link #CANCELLED_TOOL_RESULT} 的占位结果。
     * 序列尾部仍悬空的 toolCalls 会在末尾追加补偿消息。
     *
     * @param messages                 原始消息序列（不会被修改）
     * @param extraCoveredToolCallIds  额外视为已覆盖的 toolCallId 集合。
     *                                 用于工具循环场景中排除 prompt 内即将随本轮请求发送、
     *                                 但尚未落库的真实工具结果，避免重复补偿
     * @return 修复后的消息序列；无悬空 toolCalls 时返回原序列
     */
    public static List<Message> repairInMemory(List<Message> messages, Set<String> extraCoveredToolCallIds) {
        List<Message> repaired = new ArrayList<>(messages.size() + 2);
        // 尚未被 tool 结果消息覆盖的 toolCall 列表
        List<AssistantMessage.ToolCall> dangling = new ArrayList<>();
        boolean changed = false;
        for (Message message : messages) {
            if (!dangling.isEmpty()) {
                if (message instanceof ToolResponseMessage trm) {
                    removeCovered(dangling, trm);
                } else {
                    // assistant(toolCalls) 之后出现了非 tool 消息：为剩余未覆盖的 toolCall 插入补偿
                    repaired.add(buildToolResponseMessage(dangling));
                    dangling.clear();
                    changed = true;
                }
            }
            repaired.add(message);
            if (message instanceof AssistantMessage am && !am.getToolCalls().isEmpty()) {
                am.getToolCalls().stream()
                        .filter(toolCall -> !extraCoveredToolCallIds.contains(toolCall.id()))
                        .forEach(dangling::add);
            }
        }
        if (!dangling.isEmpty()) {
            // 序列尾部悬空（STOP 中断场景）：在末尾追加补偿
            repaired.add(buildToolResponseMessage(dangling));
            changed = true;
        }
        return changed ? repaired : messages;
    }

    /**
     * 检查消息序列中是否存在未被 tool 结果消息覆盖的 toolCalls，若有则生成一条聚合的
     * 补偿 {@link ToolResponseMessage}（包含所有未覆盖 toolCallId 的占位结果），
     * 用于在 STOP / 连接关闭中断时追加到 ChatMemory 末尾，保证记忆序列完整。
     *
     * @param messages 当前记忆消息序列
     * @return 需要追加的补偿消息；无悬空 toolCalls 时返回 {@link Optional#empty()}
     */
    public static Optional<ToolResponseMessage> buildTailCompensation(List<Message> messages) {
        List<AssistantMessage.ToolCall> dangling = new ArrayList<>();
        for (Message message : messages) {
            if (message instanceof AssistantMessage am && !am.getToolCalls().isEmpty()) {
                dangling.addAll(am.getToolCalls());
            } else if (message instanceof ToolResponseMessage trm) {
                removeCovered(dangling, trm);
            }
        }
        if (dangling.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(buildToolResponseMessage(dangling));
    }

    /**
     * 补偿修复指定会话记忆中悬空的工具调用记录。
     * <p>
     * 流程：从 {@link JpaChatMemoryRepository} 取出当前对话全部消息，
     * 调用 {@link #buildTailCompensation(List)} 构造聚合补偿消息，
     * 若存在悬空则写回。补偿失败仅记日志，不影响中断主流程。
     * <p>
     * 行为来源：原 {@code AgentExecutor.compensateDanglingToolCalls} 方法平移到此。
     *
     * @param conversationId 会话 ID；为 null 时无操作
     */
    public void compensate(String conversationId) {
        if (conversationId == null) {
            return;
        }
        try {
            List<Message> messages = chatMemoryRepository.findByConversationId(conversationId);
            Optional<ToolResponseMessage> compensation = buildTailCompensation(messages);
            if (compensation.isPresent()) {
                ToolResponseMessage msg = compensation.get();
                chatMemoryRepository.add(conversationId, List.of(msg));
                log.debug("会话 {} 已补偿 {} 条被中断的工具调用结果",
                        conversationId, msg.getResponses().size());
            }
        } catch (Exception e) {
            log.error("补偿修复会话 {} 被中断的工具调用记忆失败", conversationId, e);
        }
    }

    /**
     * 从悬空 toolCall 列表中移除已被给定 tool 结果消息覆盖的项。
     *
     * @param dangling            悬空 toolCall 列表（就地修改）
     * @param toolResponseMessage tool 结果消息
     */
    private static void removeCovered(List<AssistantMessage.ToolCall> dangling, ToolResponseMessage toolResponseMessage) {
        Set<String> coveredIds = toolResponseMessage.getResponses().stream()
                .map(ToolResponseMessage.ToolResponse::id)
                .collect(Collectors.toSet());
        dangling.removeIf(toolCall -> coveredIds.contains(toolCall.id()));
    }

    /**
     * 为给定的悬空 toolCall 列表构建一条合成的 tool 结果消息，
     * 每个 toolCallId 对应一条内容为 {@link #CANCELLED_TOOL_RESULT} 的占位结果。
     *
     * @param toolCalls 悬空 toolCall 列表
     * @return 合成的 tool 结果消息
     */
    private static ToolResponseMessage buildToolResponseMessage(List<AssistantMessage.ToolCall> toolCalls) {
        List<ToolResponseMessage.ToolResponse> responses = toolCalls.stream()
                .map(toolCall -> new ToolResponseMessage.ToolResponse(
                        toolCall.id(), toolCall.name(), CANCELLED_TOOL_RESULT))
                .toList();
        return ToolResponseMessage.builder()
                .responses(responses)
                .metadata(Map.of())
                .build();
    }
}