package com.sfc.ai.core.advisor;

import com.xiaotao.saltedfishcloud.utils.StringUtils;
import org.jspecify.annotations.NonNull;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.model.ChatResponse;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public abstract class SfcBaseAdvisor implements BaseAdvisor {

    @Override
    public @NonNull Flux<ChatClientResponse> adviseStream(@NonNull ChatClientRequest chatClientRequest, StreamAdvisorChain streamAdvisorChain) {

        Flux<ChatClientResponse> chatClientResponseFlux = Mono.just(chatClientRequest)
                .publishOn(getScheduler())
                .map(request -> this.before(request, streamAdvisorChain))
                .flatMapMany(streamAdvisorChain::nextStream);

        return chatClientResponseFlux.map(response -> {
            if (isFinish(response)) {
                response = after(response, streamAdvisorChain);
            }
            return response;
        });
    }


    protected boolean isFinish(ChatClientResponse response) {
        ChatResponse chatResponse = response.chatResponse();
        return chatResponse != null && chatResponse.getResults()
                .stream()
                .anyMatch(result -> {
                    String finishReason = result.getMetadata().getFinishReason();
                    return StringUtils.hasText(finishReason) && !"_UNKNOWN".equals(finishReason);
                });
    }
}
