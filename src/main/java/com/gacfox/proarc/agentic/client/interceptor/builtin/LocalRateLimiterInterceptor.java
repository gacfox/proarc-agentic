package com.gacfox.proarc.agentic.client.interceptor.builtin;

import com.gacfox.proarc.agentic.exception.LlmLocalRateLimitException;
import com.gacfox.proarc.agentic.client.interceptor.LlmInterceptor;
import com.gacfox.proarc.agentic.client.interceptor.LlmInterceptorChain;
import com.gacfox.proarc.agentic.model.openai.ModelInfo;
import com.gacfox.proarc.agentic.model.openai.ModelRequest;
import com.gacfox.proarc.agentic.model.openai.ModelResponse;
import reactor.core.publisher.Flux;

/**
 * 基于令牌桶算法的单机QPS限流拦截器
 */
public class LocalRateLimiterInterceptor implements LlmInterceptor {
    private final int maxQps;
    private double availableTokens;
    private long lastRefillTimeNanos;

    /**
     * 构建单机QPS限流器
     *
     * @param maxQps 最大QPS
     */
    public LocalRateLimiterInterceptor(int maxQps) {
        if (maxQps <= 0) {
            throw new IllegalArgumentException("maxQps must be positive, got: " + maxQps);
        }
        this.maxQps = maxQps;
        this.availableTokens = maxQps;
        this.lastRefillTimeNanos = System.nanoTime();
    }

    @Override
    public ModelResponse interceptBlocking(ModelRequest request, ModelInfo modelInfo, LlmInterceptorChain chain) {
        if (!tryAcquire()) {
            throw new LlmLocalRateLimitException(
                    "Local rate limit exceeded (maxQps=" + maxQps + ")",
                    modelInfo.getProvider(), modelInfo.getModel());
        }
        return chain.nextBlocking(request);
    }

    @Override
    public Flux<ModelResponse> interceptStreaming(ModelRequest request, ModelInfo modelInfo, LlmInterceptorChain chain) {
        if (tryAcquire()) {
            return chain.nextStreaming(request);
        }
        return Flux.error(new LlmLocalRateLimitException(
                "Local rate limit exceeded (maxQps=" + maxQps + ")",
                modelInfo.getProvider(), modelInfo.getModel()));
    }

    @Override
    public int getOrder() {
        return -100;
    }

    private synchronized boolean tryAcquire() {
        refill();
        if (availableTokens >= 1.0) {
            availableTokens -= 1.0;
            return true;
        }
        return false;
    }

    private void refill() {
        long nowNanos = System.nanoTime();
        double elapsedSeconds = (nowNanos - lastRefillTimeNanos) / 1_000_000_000.0;
        double tokensToAdd = elapsedSeconds * maxQps;
        availableTokens = Math.min(availableTokens + tokensToAdd, maxQps);
        lastRefillTimeNanos = nowNanos;
    }
}
