package com.sfc.ai.core.memory;

import lombok.Builder;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Builder
@RequiredArgsConstructor
public class SfcChatMemory implements ChatMemory {
    private final JpaChatMemoryRepository chatMemoryRepository;

    @Override
    @Transactional
    public void add(@NonNull String conversationId, @NonNull List<Message> messages) {
        chatMemoryRepository.add(conversationId, messages);
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
