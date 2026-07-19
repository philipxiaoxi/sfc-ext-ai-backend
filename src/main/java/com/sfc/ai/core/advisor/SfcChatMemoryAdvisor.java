package com.sfc.ai.core.advisor;

import com.sfc.ai.core.adapter.LlmChatAdapter;
import com.sfc.ai.core.memory.ChatMemoryRepairer;
import com.sfc.ai.core.message.ReasoningAssistantMessage;
import com.xiaotao.saltedfishcloud.utils.StringUtils;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.util.Assert;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
public class SfcChatMemoryAdvisor extends SfcBaseAdvisor implements BaseChatMemoryAdvisor {
    private final LlmChatAdapter llmChatAdapter;
    private final ChatMemory chatMemory;

    private final ChatResponseMessageBuilder messageBuilder = new ChatResponseMessageBuilder();

    public SfcChatMemoryAdvisor(LlmChatAdapter llmChatAdapter, ChatMemory chatMemory) {
        this.llmChatAdapter = llmChatAdapter;
        this.chatMemory = chatMemory;
    }


    private static class ChatResponseMessageBuilder {
        private final StringBuilder reasoningBuilder = new StringBuilder();
        private final StringBuilder textBuilder = new StringBuilder();

        public void addReasoning(String reasoningBuilder) {
            if (StringUtils.hasText(reasoningBuilder)) {
                this.reasoningBuilder.append(reasoningBuilder);
            }
        }

        public void addText(String text) {
            if (StringUtils.hasText(text)) {
                this.textBuilder.append(text);
            }
        }

        public String getReasoning() {
            return reasoningBuilder.toString();
        }

        public String getText() {
            return textBuilder.toString();
        }

        public void reset() {
            this.reasoningBuilder.setLength(0);
            this.textBuilder.setLength(0);
        }
    }

    @Override
    public @NonNull ChatClientRequest before(ChatClientRequest chatClientRequest, @NonNull AdvisorChain advisorChain) {
        String conversationId = getConversationId(chatClientRequest.context());
        this.messageBuilder.reset();

        // 1. Retrieve the chat memory for the current conversation.
        // 加载记忆后先做内存级完整性修复：为被 STOP/连接关闭中断而悬空的 assistant(toolCalls)
        // 补插“已中断”占位 tool 结果消息，避免后续请求被 LLM API 以 400 拒绝。
        // prompt 中即将随本轮工具循环发送、但尚未落库的真实工具结果视为已覆盖，不做重复补偿。
        List<Message> promptMessages = chatClientRequest.prompt().getInstructions();
        Set<String> promptToolResultIds = promptMessages.stream()
                .filter(ToolResponseMessage.class::isInstance)
                .map(ToolResponseMessage.class::cast)
                .flatMap(trm -> trm.getResponses().stream())
                .map(ToolResponseMessage.ToolResponse::id)
                .collect(Collectors.toSet());
        List<Message> memoryMessages = ChatMemoryRepairer.repairInMemory(
                chatMemory.get(conversationId), promptToolResultIds);

        // 2. Advise the request messages list.
        List<Message> processedMessages = new ArrayList<>();
        if (!isMemoryAlreadyInPrompt(promptMessages, memoryMessages)) {
            processedMessages.addAll(memoryMessages);
        }
        processedMessages.addAll(promptMessages);

        // 2.1. Ensure system message, if present, appears first in the list.
        for (int i = 0; i < processedMessages.size(); i++) {
            if (processedMessages.get(i) instanceof SystemMessage) {
                Message systemMessage = processedMessages.remove(i);
                processedMessages.addFirst(systemMessage);
                break;
            }
        }

        // 3. Create a new request with the advised messages.
        ChatClientRequest processedChatClientRequest = chatClientRequest.mutate()
                .prompt(chatClientRequest.prompt().mutate().messages(processedMessages).build())
                .build();

        // 4. Add the new user message to the conversation memory.
        Message userMessage = processedChatClientRequest.prompt().getLastUserOrToolResponseMessage();
        this.chatMemory.add(conversationId, userMessage);

        return processedChatClientRequest;
    }


    private static boolean isMemoryAlreadyInPrompt(List<Message> promptMessages, List<Message> memoryMessages) {
        if (memoryMessages.isEmpty()) {
            return true;
        }
        if (promptMessages.size() < memoryMessages.size()) {
            return false;
        }
        for (int offset = 0; offset <= promptMessages.size() - memoryMessages.size(); offset++) {
            if (startsWith(promptMessages, memoryMessages, offset)) {
                return true;
            }
        }
        return false;
    }

    private static boolean startsWith(List<Message> messages, List<Message> prefix, int offset) {
        if (messages.size() - offset < prefix.size()) {
            return false;
        }
        for (int i = 0; i < prefix.size(); i++) {
            if (!messages.get(i + offset).equals(prefix.get(i))) {
                return false;
            }
        }
        return true;
    }


    @Override
    public @NonNull ChatClientResponse after(ChatClientResponse chatClientResponse, @NonNull AdvisorChain advisorChain) {
        String conversationId = getConversationId(chatClientResponse.context());
        // LLM 完成响应，持久化记录本次响应所有的 思考内容、响应正文 和 工具调用请求

        List<AssistantMessage> responseMessageList = Optional.ofNullable(chatClientResponse.chatResponse())
                .map(ChatResponse::getResult)
                .map(Generation::getOutput)
                .stream()
                .peek(msg -> {
                    messageBuilder.addReasoning(llmChatAdapter.extractReasoningContent(msg));
                    messageBuilder.addText(msg.getText());
                })
                .toList();

        List<AssistantMessage.ToolCall> toolCalls = responseMessageList
                .stream()
                .flatMap(e -> e.getToolCalls().stream())
                .toList();

        log.debug("[{}]LLM 思考内容: {}", conversationId, messageBuilder.getReasoning());
        log.debug("[{}]LLM 文本响应: {}", conversationId, messageBuilder.getText());
        log.debug("[{}]LLM 工具调用: {}", conversationId, toolCalls.stream().map(Record::toString).collect(Collectors.joining(";")));


        Optional.ofNullable(chatClientResponse.chatResponse())
                .map(ChatResponse::getResult)
                .map(Generation::getOutput)
                .ifPresent(msg -> chatMemory.add(conversationId, new ReasoningAssistantMessage(
                        messageBuilder.getText(),
                        messageBuilder.getReasoning(),
                        msg.getMetadata(),
                        toolCalls,
                        msg.getMedia()
                )));
        return chatClientResponse;
    }

    @Override
    public @NonNull Flux<ChatClientResponse> adviseStream(@NonNull ChatClientRequest chatClientRequest, @NonNull StreamAdvisorChain streamAdvisorChain) {

        Assert.notNull(chatClientRequest, "chatClientRequest cannot be null");
        Assert.notNull(streamAdvisorChain, "streamAdvisorChain cannot be null");
        Assert.notNull(getScheduler(), "scheduler cannot be null");

        Flux<ChatClientResponse> chatClientResponseFlux = Mono.just(chatClientRequest)
                .publishOn(getScheduler())
                .map(request -> this.before(request, streamAdvisorChain))
                .flatMapMany(streamAdvisorChain::nextStream);


        return chatClientResponseFlux
                .doOnNext(response -> {
                    // 提取思考内容和文本响应
                    Optional.ofNullable(response.chatResponse())
                            .map(ChatResponse::getResult)
                            .ifPresent(generation -> {
                                AssistantMessage output = generation.getOutput();
                                messageBuilder.addReasoning(llmChatAdapter.extractReasoningContent(output));
                                messageBuilder.addText(output.getText());
                            });

                }).map(response -> {
                    if (this.isFinish(response)) {
                        response = after(response, streamAdvisorChain);
                    }
                    return response;
                });
    }

    @Override
    public int getOrder() {
        return 0;
    }
}