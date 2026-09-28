package com.gacfox.proarc.agentic.client;

import com.gacfox.proarc.agentic.client.header.LlmHeaderProvider;
import com.gacfox.proarc.agentic.client.interceptor.LlmInterceptor;
import com.gacfox.proarc.agentic.exception.LlmException;
import com.gacfox.proarc.agentic.model.openai.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Builder;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

import java.util.List;
import java.util.Map;

/**
 * OpenAI兼容端点大语言模型客户端
 */
public final class OpenAiLlmClient extends AbstractLlmClient {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final LlmHeaderProvider headerProvider;

    @Builder
    private OpenAiLlmClient(ModelInfo modelInfo, List<LlmInterceptor> interceptors, HttpClient httpClient,
                            LlmHeaderProvider headerProvider) {
        super(modelInfo, interceptors, httpClient);
        this.headerProvider = headerProvider;
    }

    @Override
    protected ModelResponse doBlockingChat(ModelRequest modelRequest) {
        try {
            modelRequest.setStream(true);
            return doStreamingChat(modelRequest)
                    .collectList()
                    .filter(chunks -> !chunks.isEmpty())
                    .map(ModelResponse::mergeStreamChunks)
                    .block();
        } catch (Exception e) {
            throw mapException(e);
        }
    }

    private void applyHeaders(HttpHeaders headers, ModelRequest modelRequest) {
        headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + modelInfo.getSk());
        headers.set(HttpHeaders.ACCEPT, MediaType.TEXT_EVENT_STREAM_VALUE);
        if (modelInfo.getHeaders() != null) {
            modelInfo.getHeaders().forEach(headers::set);
        }
        if (headerProvider != null) {
            Map<String, String> dynamicHeaders = headerProvider.resolve(modelInfo, modelRequest);
            if (dynamicHeaders != null) {
                dynamicHeaders.forEach(headers::set);
            }
        }
    }

    @Override
    protected Flux<ModelResponse> doStreamingChat(ModelRequest modelRequest) {
        modelRequest.setStream(true);

        return webClient.post()
                .uri(modelInfo.getEndpoint())
                .headers(headers -> applyHeaders(headers, modelRequest))
                .bodyValue(modelRequest)
                .retrieve()
                .bodyToFlux(String.class)
                .filter(data -> data != null && !"[DONE]".equals(data.trim()))
                .flatMap(data -> {
                    try {
                        return Mono.just(OBJECT_MAPPER.readValue(data, ModelResponse.class));
                    } catch (JsonProcessingException e) {
                        return Mono.error(new RuntimeException("Failed to parse streaming response chunk", e));
                    }
                })
                .onErrorMap(e -> e instanceof LlmException ? e : mapException(e));
    }
}
