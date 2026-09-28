package com.gacfox.proarc.agentic.client;

import com.gacfox.proarc.agentic.exception.*;
import com.gacfox.proarc.agentic.client.interceptor.LlmInterceptor;
import com.gacfox.proarc.agentic.client.interceptor.LlmInterceptorChain;
import com.gacfox.proarc.agentic.model.ChatRequest;
import com.gacfox.proarc.agentic.model.openai.ChatTemplateKwargs;
import com.gacfox.proarc.agentic.model.openai.ModelInfo;
import com.gacfox.proarc.agentic.model.openai.ModelRequest;
import com.gacfox.proarc.agentic.model.openai.ModelResponse;
import com.gacfox.proarc.agentic.model.openai.OpenAiErrorResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

import javax.net.ssl.SSLException;
import java.net.ConnectException;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeoutException;

/**
 * 抽象大语言模型客户端
 */
public abstract class AbstractLlmClient implements LlmClient {
    @Getter
    protected final ModelInfo modelInfo;
    protected final WebClient webClient;
    protected final List<LlmInterceptor> interceptors;

    private static final ObjectMapper ERROR_OBJECT_MAPPER = new ObjectMapper();

    /**
     * 构建大语言模型客户端
     *
     * @param modelInfo    模型配置
     * @param interceptors 拦截器列表
     * @param httpClient   reactor-netty的HttpClient
     */
    public AbstractLlmClient(ModelInfo modelInfo, List<LlmInterceptor> interceptors, HttpClient httpClient) {
        this.modelInfo = modelInfo;
        this.interceptors = !CollectionUtils.isEmpty(interceptors)
                ? interceptors.stream().sorted(Comparator.comparingInt(LlmInterceptor::getOrder)).toList()
                : Collections.emptyList();
        this.webClient = buildWebClient(httpClient);
    }

    private WebClient buildWebClient(HttpClient httpClient) {
        return WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.CACHE_CONTROL, "no-cache")
                .defaultHeader(HttpHeaders.USER_AGENT, "ProArc-Agentic-Client/1.0")
                .filter(ExchangeFilterFunction.ofRequestProcessor(request -> {
                    URI uri = request.url();
                    String host = uri.getHost();
                    int port = uri.getPort();
                    String hostHeader = (port != -1 && port != 80 && port != 443)
                            ? host + ":" + port
                            : host;
                    ClientRequest newRequest = ClientRequest.from(request)
                            .header(HttpHeaders.HOST, hostHeader)
                            .build();
                    return Mono.just(newRequest);
                }))
                .build();
    }

    @Override
    public final ModelResponse blockingChat(ChatRequest chatRequest) {
        ModelRequest modelRequest = toModelRequest(chatRequest, false);
        return buildBlockingChain(this.interceptors, 0).nextBlocking(modelRequest);
    }

    @Override
    public final Flux<ModelResponse> streamingChat(ChatRequest chatRequest) {
        ModelRequest modelRequest = toModelRequest(chatRequest, true);
        return buildStreamingChain(this.interceptors, 0).nextStreaming(modelRequest);
    }

    private ModelRequest toModelRequest(ChatRequest chatRequest, boolean stream) {
        ModelRequest modelRequest = ModelRequest.builder()
                .model(modelInfo.getModel())
                .stream(stream)
                .messages(chatRequest.getMessages())
                .temperature(chatRequest.getTemperature())
                .topP(chatRequest.getTopP())
                .topK(chatRequest.getTopK())
                .presencePenalty(chatRequest.getPresencePenalty())
                .frequencyPenalty(chatRequest.getFrequencyPenalty())
                .seed(chatRequest.getSeed())
                .maxTokens(chatRequest.getMaxTokens())
                .tools(chatRequest.getTools())
                .toolChoice(chatRequest.getToolChoice())
                .build();
        if (!CollectionUtils.isEmpty(modelInfo.getCapabilities()) &&
                modelInfo.getCapabilities().contains(ModelInfo.CAPABILITY_REASONING) &&
                chatRequest.getEnableThinking() != null) {
            modelRequest.setChatTemplateKwargs(ChatTemplateKwargs.builder()
                    .enableThinking(chatRequest.getEnableThinking())
                    .build());
        }
        return modelRequest;
    }

    private LlmInterceptorChain buildBlockingChain(List<LlmInterceptor> list, int index) {
        return new LlmInterceptorChain() {
            @Override
            public ModelResponse nextBlocking(ModelRequest request) {
                if (index < list.size()) {
                    return list.get(index).interceptBlocking(request, modelInfo, buildBlockingChain(list, index + 1));
                }
                return doBlockingChat(request);
            }

            @Override
            public Flux<ModelResponse> nextStreaming(ModelRequest request) {
                throw new UnsupportedOperationException("blocking chain does not support streaming");
            }
        };
    }

    private LlmInterceptorChain buildStreamingChain(List<LlmInterceptor> list, int index) {
        return new LlmInterceptorChain() {
            @Override
            public ModelResponse nextBlocking(ModelRequest request) {
                throw new UnsupportedOperationException("streaming chain does not support blocking");
            }

            @Override
            public Flux<ModelResponse> nextStreaming(ModelRequest request) {
                if (index < list.size()) {
                    return list.get(index).interceptStreaming(request, modelInfo, buildStreamingChain(list, index + 1));
                }
                return doStreamingChat(request);
            }
        };
    }

    protected abstract ModelResponse doBlockingChat(ModelRequest modelRequest);

    protected abstract Flux<ModelResponse> doStreamingChat(ModelRequest modelRequest);

    /**
     * 将底层异常映射为框架异常
     *
     * @param throwable 原始异常
     * @return 框架异常
     */
    protected LlmException mapException(Throwable throwable) {
        String provider = modelInfo.getProvider();
        String model = modelInfo.getModel();

        // WebClient HTTP 错误码响应
        if (throwable instanceof WebClientResponseException responseException) {
            return mapHttpException(responseException.getStatusCode().value(),
                    responseException.getResponseBodyAsString(), responseException);
        }

        // 超时异常
        if (throwable instanceof TimeoutException
                || throwable instanceof io.netty.handler.timeout.ReadTimeoutException
                || throwable instanceof java.nio.channels.ClosedChannelException) {
            return new LlmTimeoutException("LLM call timed out: " + throwable.getMessage(),
                    throwable, provider, model);
        }

        // 网络异常
        if (throwable instanceof WebClientRequestException wcre) {
            Throwable cause = wcre.getCause();
            if (cause instanceof UnknownHostException) {
                return new LlmNetworkException("DNS resolution failed: " + cause.getMessage(),
                        cause, provider, model);
            }
            if (cause instanceof ConnectException) {
                return new LlmNetworkException("Connection refused: " + cause.getMessage(),
                        cause, provider, model);
            }
            if (cause instanceof SSLException) {
                return new LlmNetworkException("SSL handshake failed: " + cause.getMessage(),
                        cause, provider, model);
            }
            return new LlmNetworkException("Network error: " + wcre.getMessage(),
                    wcre, provider, model);
        }

        // 兜底异常
        if (throwable instanceof LlmException) {
            return (LlmException) throwable;
        }
        return new LlmNetworkException("Unexpected error: " + throwable.getMessage(),
                throwable, provider, model);
    }

    private LlmProviderException mapHttpException(int statusCode, String body, Throwable cause) {
        String provider = modelInfo.getProvider();
        String model = modelInfo.getModel();

        String providerMessage = null;
        String providerErrorCode = null;
        if (StringUtils.hasText(body)) {
            try {
                OpenAiErrorResponse resp = ERROR_OBJECT_MAPPER.readValue(body, OpenAiErrorResponse.class);
                if (resp != null && resp.getError() != null) {
                    providerMessage = resp.getError().getMessage();
                    providerErrorCode = resp.getError().getCode();
                }
            } catch (Exception ignored) {
            }
        }
        if (StringUtils.hasText(providerMessage)) {
            providerMessage = providerMessage.trim();
            if (providerMessage.length() > 500) {
                providerMessage = providerMessage.substring(0, 500) + "... [truncated]";
            }
        }

        if (statusCode == 401 || statusCode == 403) {
            String message = buildErrorMessage("Authentication failed (HTTP " + statusCode + ")", providerMessage, providerErrorCode);
            return new LlmAuthException(message, cause, provider, model, statusCode, body, providerErrorCode);
        }
        if (statusCode == 400) {
            String message = buildErrorMessage("Bad request (HTTP 400)", providerMessage, providerErrorCode);
            return new LlmBadRequestException(message, cause, provider, model, statusCode, body, providerErrorCode);
        }
        if (statusCode == 404) {
            String message = buildErrorMessage("Not found (HTTP 404)", providerMessage, providerErrorCode);
            return new LlmNotFoundException(message, cause, provider, model, statusCode, body, providerErrorCode);
        }
        if (statusCode == 429) {
            Long retryAfter = null;
            if (cause instanceof WebClientResponseException e && e.getHeaders().getFirst("Retry-After") != null) {
                try {
                    String retryAfterStr = e.getHeaders().getFirst("Retry-After");
                    if (StringUtils.hasText(retryAfterStr)) {
                        retryAfter = Long.parseLong(retryAfterStr) * 1000L;
                    }
                } catch (NumberFormatException ignored) {
                }
            }
            String message = buildErrorMessage("Rate limited (HTTP 429)", providerMessage, providerErrorCode);
            return new LlmRateLimitException(message, cause, provider, model, statusCode, body, providerErrorCode, retryAfter);
        }
        if (statusCode >= 500) {
            String message = buildErrorMessage("Provider server error (HTTP " + statusCode + ")", providerMessage, providerErrorCode);
            return new LlmServerException(message, cause, provider, model, statusCode, body, providerErrorCode);
        }
        String message = buildErrorMessage("Provider error (HTTP " + statusCode + ")", providerMessage, providerErrorCode);
        return new LlmProviderException(message, cause, LlmErrorCode.PROVIDER_ERROR, provider, model, false,
                statusCode, body, providerErrorCode, null);
    }

    private static String buildErrorMessage(String base, String providerMessage, String providerErrorCode) {
        if (!StringUtils.hasText(providerMessage)) {
            return base;
        }
        if (StringUtils.hasText(providerErrorCode)) {
            return base + ": " + providerErrorCode + " - " + providerMessage;
        }
        return base + ": " + providerMessage;
    }
}
