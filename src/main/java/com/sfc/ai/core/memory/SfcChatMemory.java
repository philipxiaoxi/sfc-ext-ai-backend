package com.sfc.ai.core.memory;

import lombok.Builder;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.Message;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Builder
@RequiredArgsConstructor
public class SfcChatMemory implements ChatMemory {
    private final ChatMemoryRepository chatMemoryRepository;

    @Override
    @Transactional
    public void add(@NonNull String conversationId,@NonNull List<Message> messages) {
        List<Message> memoryMessageList = new ArrayList<>(get(conversationId));
        memoryMessageList.addAll(messages);
        this.clear(conversationId);
        chatMemoryRepository.saveAll(conversationId, memoryMessageList);
    }

    @Override
    @NonNull
    public List<Message> get(@NonNull String conversationId) {
        return chatMemoryRepository.findByConversationId(conversationId);
    }

    @Override
    @Transactional
    public void clear(@NonNull String conversationId) {
        chatMemoryRepository.deleteByConversationId(conversationId);
    }
}
