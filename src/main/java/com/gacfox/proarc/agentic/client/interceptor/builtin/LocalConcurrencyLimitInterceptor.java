package com.gacfox.proarc.agentic.client.interceptor.builtin;

import com.gacfox.proarc.agentic.exception.LlmConcurrencyLimitException;
import com.gacfox.proarc.agentic.client.interceptor.LlmInterceptor;
import com.gacfox.proarc.agentic.client.interceptor.LlmInterceptorChain;
import com.gacfox.proarc.agentic.model.openai.ModelInfo;
import com.gacfox.proarc.agentic.model.openai.ModelRequest;
import com.gacfox.proarc.agentic.model.openai.ModelResponse;
import reactor.core.publisher.Flux;

import java.util.concurrent.Semaphore;

/**
 * 基于信号量的单机并发数限流拦截器
 */
public class LocalConcurrencyLimitInterceptor implements LlmInterceptor {
    private final int maxConcurrency;
    private final Semaphore semaphore;

    /**
     * 构建单机并发数限流器
     *
     * @param maxConcurrency 最大并发数
     */
    public LocalConcurrencyLimitInterceptor(int maxConcurrency) {
        if (maxConcurrency <= 0) {
            throw new IllegalArgumentException("maxConcurrency must be positive, got: " + maxConcurrency);
        }
        this.maxConcurrency = maxConcurrency;
        this.semaphore = new Semaphore(maxConcurrency);
    }

    @Override
    public ModelResponse interceptBlocking(ModelRequest request, ModelInfo modelInfo, LlmInterceptorChain chain) {
        if (!semaphore.tryAcquire()) {
            throw new LlmConcurrencyLimitException(
                    "Local concurrency limit exceeded (maxConcurrency=" + maxConcurrency + ")",
                    modelInfo.getProvider(), modelInfo.getModel());
        }
        try {
            return chain.nextBlocking(request);
        } finally {
            semaphore.release();
        }
    }

    @Override
    public Flux<ModelResponse> interceptStreaming(ModelRequest request, ModelInfo modelInfo, LlmInterceptorChain chain) {
        if (!semaphore.tryAcquire()) {
            return Flux.error(new LlmConcurrencyLimitException(
                    "Local concurrency limit exceeded (maxConcurrency=" + maxConcurrency + ")",
                    modelInfo.getProvider(), modelInfo.getModel()));
        }
        return chain.nextStreaming(request)
                .doFinally(signal -> semaphore.release());
    }

    @Override
    public int getOrder() {
        return -110;
    }
}
