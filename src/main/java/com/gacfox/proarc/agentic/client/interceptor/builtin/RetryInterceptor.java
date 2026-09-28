package com.gacfox.proarc.agentic.client.interceptor.builtin;

import com.gacfox.proarc.agentic.exception.LlmException;
import com.gacfox.proarc.agentic.exception.LlmRateLimitException;
import com.gacfox.proarc.agentic.exception.LlmRetryExhaustedException;
import com.gacfox.proarc.agentic.client.interceptor.LlmInterceptor;
import com.gacfox.proarc.agentic.client.interceptor.LlmInterceptorChain;
import com.gacfox.proarc.agentic.model.openai.ModelInfo;
import com.gacfox.proarc.agentic.model.openai.ModelRequest;
import com.gacfox.proarc.agentic.model.openai.ModelResponse;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 重试拦截器，优先尝试使用Retry-After响应头，否则自动指数退避
 */
public class RetryInterceptor implements LlmInterceptor {
    private final int maxRetries;
    private final long baseDelayMillis;
    private final long maxDelayMillis;

    /**
     * 采用默认重试配置构建，重试20次，时间范围1s-30s
     */
    public RetryInterceptor() {
        this(20, 1000, 30000);
    }

    /**
     * 构建重试拦截器
     *
     * @param maxRetries      最大重试次数
     * @param baseDelayMillis 基础延迟时间（毫秒）
     * @param maxDelayMillis  最大延迟时间（毫秒）
     */
    public RetryInterceptor(int maxRetries, long baseDelayMillis, long maxDelayMillis) {
        if (maxRetries < 0) {
            throw new IllegalArgumentException("maxRetries must be non-negative, got: " + maxRetries);
        }
        this.maxRetries = maxRetries;
        this.baseDelayMillis = baseDelayMillis;
        this.maxDelayMillis = maxDelayMillis;
    }

    @Override
    public ModelResponse interceptBlocking(ModelRequest request, ModelInfo modelInfo, LlmInterceptorChain chain) {
        LlmException lastError = null;
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                return chain.nextBlocking(request);
            } catch (LlmException e) {
                lastError = e;
                if (!e.isRetryable()) {
                    throw e;
                }
                if (attempt == maxRetries) {
                    break;
                }
                sleep(computeDelay(attempt, e));
            }
        }
        throw new LlmRetryExhaustedException(
                "Retry exhausted after " + (maxRetries + 1) + " attempts",
                modelInfo.getProvider(), modelInfo.getModel(),
                maxRetries + 1, lastError);
    }

    @Override
    public Flux<ModelResponse> interceptStreaming(ModelRequest request, ModelInfo modelInfo, LlmInterceptorChain chain) {
        AtomicBoolean emitted = new AtomicBoolean();
        return chain.nextStreaming(request)
                .doOnNext(chunk -> emitted.set(true))
                .retryWhen(Retry.withThrowable(companion -> companion
                        .zipWith(Flux.range(1, maxRetries + 1))
                        .flatMap(tuple -> {
                            Throwable err = tuple.getT1();
                            int attempt = tuple.getT2();
                            if (emitted.get()) {
                                return Mono.error(err);
                            }
                            if (!(err instanceof LlmException le) || !le.isRetryable()) {
                                return Mono.error(err);
                            }
                            if (attempt > maxRetries) {
                                return Mono.error(new LlmRetryExhaustedException(
                                        "Retry exhausted after " + attempt + " attempts",
                                        modelInfo.getProvider(), modelInfo.getModel(),
                                        attempt, err));
                            }
                            long delay = computeDelay(attempt - 1, le);
                            return Mono.delay(Duration.ofMillis(delay));
                        })
                ));
    }

    @Override
    public int getOrder() {
        return 100;
    }

    long computeDelay(int attempt, LlmException e) {
        // 429 时优先使用 Retry-After
        if (e instanceof LlmRateLimitException rl && rl.getRetryAfterMillis() != null) {
            return rl.getRetryAfterMillis();
        }
        // 指数退避 + jitter
        long delay = (long) (baseDelayMillis * Math.pow(2, attempt));
        long jitter = ThreadLocalRandom.current().nextLong(Math.min(delay / 2, 500) + 1);
        delay = delay + jitter;
        return Math.min(delay, maxDelayMillis);
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Retry sleep interrupted", e);
        }
    }
}
