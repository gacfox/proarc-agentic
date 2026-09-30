package com.gacfox.proarc.agentic.client.interceptor.builtin;

import com.gacfox.proarc.agentic.client.interceptor.LlmInterceptorChain;
import com.gacfox.proarc.agentic.exception.LlmAuthException;
import com.gacfox.proarc.agentic.exception.LlmLocalRateLimitException;
import com.gacfox.proarc.agentic.exception.LlmRateLimitException;
import com.gacfox.proarc.agentic.exception.LlmRetryExhaustedException;
import com.gacfox.proarc.agentic.exception.LlmTimeoutException;
import com.gacfox.proarc.agentic.model.openai.ModelInfo;
import com.gacfox.proarc.agentic.model.openai.ModelRequest;
import com.gacfox.proarc.agentic.model.openai.ModelResponse;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RetryInterceptorTest {
    private static final ModelInfo MODEL_INFO = ModelInfo.builder().provider("p").model("m").build();
    private static final ModelRequest REQUEST = ModelRequest.builder().build();
    private static final ModelResponse RESPONSE = ModelResponse.builder().build();

    private static LlmTimeoutException timeout() {
        return new LlmTimeoutException("timeout", null, "p", "m");
    }

    private static LlmInterceptorChain blockingChain(java.util.function.Supplier<ModelResponse> supplier) {
        return new LlmInterceptorChain() {
            @Override
            public ModelResponse nextBlocking(ModelRequest request) {
                return supplier.get();
            }

            @Override
            public Flux<ModelResponse> nextStreaming(ModelRequest request) {
                throw new UnsupportedOperationException();
            }
        };
    }

    private static LlmInterceptorChain streamingChain(Flux<ModelResponse> flux) {
        return new LlmInterceptorChain() {
            @Override
            public ModelResponse nextBlocking(ModelRequest request) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Flux<ModelResponse> nextStreaming(ModelRequest request) {
                return flux;
            }
        };
    }

    @Test
    void rejectsNegativeMaxRetries() {
        assertThatThrownBy(() -> new RetryInterceptor(-1, 1, 5))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void blockingRetriesOnRetryableThenSucceeds() {
        RetryInterceptor interceptor = new RetryInterceptor(3, 1, 5);
        AtomicInteger calls = new AtomicInteger();

        ModelResponse result = interceptor.interceptBlocking(REQUEST, MODEL_INFO, blockingChain(() -> {
            if (calls.incrementAndGet() < 3) {
                throw timeout();
            }
            return RESPONSE;
        }));

        assertThat(result).isSameAs(RESPONSE);
        assertThat(calls).hasValue(3);
    }

    @Test
    void blockingDoesNotRetryNonRetryable() {
        RetryInterceptor interceptor = new RetryInterceptor(3, 1, 5);
        AtomicInteger calls = new AtomicInteger();
        LlmAuthException auth = new LlmAuthException("auth", null, "p", "m", 401, null, null);

        assertThatThrownBy(() -> interceptor.interceptBlocking(REQUEST, MODEL_INFO, blockingChain(() -> {
            calls.incrementAndGet();
            throw auth;
        }))).isSameAs(auth);
        assertThat(calls).hasValue(1);
    }

    @Test
    void blockingExhaustedThrowsRetryExhausted() {
        RetryInterceptor interceptor = new RetryInterceptor(2, 1, 5);
        AtomicInteger calls = new AtomicInteger();

        assertThatThrownBy(() -> interceptor.interceptBlocking(REQUEST, MODEL_INFO, blockingChain(() -> {
            calls.incrementAndGet();
            throw timeout();
        }))).isInstanceOfSatisfying(LlmRetryExhaustedException.class, e -> {
            assertThat(e.getAttempts()).isEqualTo(3);
            assertThat(e.getLastCause()).isInstanceOf(LlmTimeoutException.class);
            assertThat(e.isRetryable()).isFalse();
        });
        assertThat(calls).hasValue(3);
    }

    @Test
    void computeDelayPrefersRetryAfterHeader() {
        RetryInterceptor interceptor = new RetryInterceptor(1, 1000, 30000);
        LlmRateLimitException rateLimit = new LlmRateLimitException("rl", null, "p", "m", 429, null, null, 5000L);

        assertThat(interceptor.computeDelay(0, rateLimit)).isEqualTo(5000L);
    }

    @Test
    void computeDelayCapsAtMaxDelay() {
        RetryInterceptor interceptor = new RetryInterceptor(5, 1000, 2000);

        assertThat(interceptor.computeDelay(10, timeout())).isLessThanOrEqualTo(2000L);
    }

    @Test
    void computeDelayGrowsExponentially() {
        RetryInterceptor interceptor = new RetryInterceptor(5, 100, 100000);

        long first = interceptor.computeDelay(0, timeout());
        long third = interceptor.computeDelay(2, timeout());
        assertThat(first).isBetween(100L, 151L);
        assertThat(third).isBetween(400L, 601L);
    }

    @Test
    void streamingRetriesBeforeAnyChunkEmitted() {
        RetryInterceptor interceptor = new RetryInterceptor(2, 1, 5);
        AtomicInteger subscriptions = new AtomicInteger();
        Flux<ModelResponse> source = Flux.defer(() -> subscriptions.getAndIncrement() == 0
                ? Flux.error(timeout())
                : Flux.just(RESPONSE));

        StepVerifier.create(interceptor.interceptStreaming(REQUEST, MODEL_INFO, streamingChain(source)))
                .expectNext(RESPONSE)
                .verifyComplete();
        assertThat(subscriptions).hasValue(2);
    }

    @Test
    void streamingDoesNotRetryAfterChunkEmitted() {
        RetryInterceptor interceptor = new RetryInterceptor(2, 1, 5);
        Flux<ModelResponse> source = Flux.concat(Flux.just(RESPONSE), Flux.error(timeout()));

        StepVerifier.create(interceptor.interceptStreaming(REQUEST, MODEL_INFO, streamingChain(source)))
                .expectNext(RESPONSE)
                .expectError(LlmTimeoutException.class)
                .verify();
    }

    @Test
    void streamingExhaustedThrowsRetryExhausted() {
        RetryInterceptor interceptor = new RetryInterceptor(1, 1, 5);

        StepVerifier.create(interceptor.interceptStreaming(REQUEST, MODEL_INFO,
                        streamingChain(Flux.error(timeout()))))
                .expectError(LlmRetryExhaustedException.class)
                .verify();
    }

    @Test
    void streamingDoesNotRetryNonRetryable() {
        RetryInterceptor interceptor = new RetryInterceptor(2, 1, 5);
        LlmAuthException auth = new LlmAuthException("auth", null, "p", "m", 401, null, null);

        StepVerifier.create(interceptor.interceptStreaming(REQUEST, MODEL_INFO,
                        streamingChain(Flux.error(auth))))
                .expectError(LlmAuthException.class)
                .verify();
    }

    @Test
    void orderIsMinus200() {
        assertThat(new RetryInterceptor().getOrder()).isEqualTo(-200);
    }

    @Test
    void retryIsOutermostAmongBuiltinInterceptors() {
        assertThat(new RetryInterceptor().getOrder())
                .isLessThan(new LocalRateLimiterInterceptor(1).getOrder())
                .isLessThan(new LocalConcurrencyLimitInterceptor(1).getOrder());
    }

    @Test
    void permitIsReleasedDuringBackoffSoOtherRequestsCanProceed() throws Exception {
        LocalConcurrencyLimitInterceptor concurrency = new LocalConcurrencyLimitInterceptor(1);
        RetryInterceptor retry = new RetryInterceptor(1, 200, 200);

        AtomicInteger attempts = new AtomicInteger();
        CountDownLatch firstAttemptFailed = new CountDownLatch(1);
        CountDownLatch secondRequestDone = new CountDownLatch(1);
        AtomicReference<Object> secondResult = new AtomicReference<>();

        // 组装顺序与 AbstractLlmClient 一致：重试（-200）在最外层，每次重试都会重新经过并发限流
        LlmInterceptorChain innermost = new LlmInterceptorChain() {
            @Override
            public ModelResponse nextBlocking(ModelRequest request) {
                if (attempts.incrementAndGet() == 1) {
                    firstAttemptFailed.countDown();
                    throw timeout();
                }
                return RESPONSE;
            }

            @Override
            public Flux<ModelResponse> nextStreaming(ModelRequest request) {
                throw new UnsupportedOperationException();
            }
        };
        LlmInterceptorChain concurrencyChain = new LlmInterceptorChain() {
            @Override
            public ModelResponse nextBlocking(ModelRequest request) {
                return concurrency.interceptBlocking(request, MODEL_INFO, innermost);
            }

            @Override
            public Flux<ModelResponse> nextStreaming(ModelRequest request) {
                throw new UnsupportedOperationException();
            }
        };

        // 第一个请求：第一次尝试失败，进入 200ms 退避，期间并发许可应已释放
        Thread retrying = new Thread(() ->
                retry.interceptBlocking(REQUEST, MODEL_INFO, concurrencyChain));
        retrying.start();
        assertThat(firstAttemptFailed.await(5, TimeUnit.SECONDS)).isTrue();

        // 第二个请求：在第一个请求退避期间应能抢到许可并成功
        Thread second = new Thread(() -> {
            try {
                secondResult.set(retry.interceptBlocking(REQUEST, MODEL_INFO, concurrencyChain));
            } catch (Exception e) {
                secondResult.set(e);
            } finally {
                secondRequestDone.countDown();
            }
        });
        second.start();

        assertThat(secondRequestDone.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(secondResult.get()).isSameAs(RESPONSE);
        retrying.join(5000);
    }

    @Test
    void eachRetryAttemptConsumesRateLimitToken() {
        LocalRateLimiterInterceptor rateLimiter = new LocalRateLimiterInterceptor(2);
        RetryInterceptor retry = new RetryInterceptor(5, 1, 5);

        AtomicInteger attempts = new AtomicInteger();
        LlmInterceptorChain innermost = new LlmInterceptorChain() {
            @Override
            public ModelResponse nextBlocking(ModelRequest request) {
                attempts.incrementAndGet();
                throw timeout();
            }

            @Override
            public Flux<ModelResponse> nextStreaming(ModelRequest request) {
                throw new UnsupportedOperationException();
            }
        };
        LlmInterceptorChain rateLimitChain = new LlmInterceptorChain() {
            @Override
            public ModelResponse nextBlocking(ModelRequest request) {
                return rateLimiter.interceptBlocking(request, MODEL_INFO, innermost);
            }

            @Override
            public Flux<ModelResponse> nextStreaming(ModelRequest request) {
                throw new UnsupportedOperationException();
            }
        };

        // maxQps=2：第 1、2 次尝试消耗令牌后，第 3 次尝试被本地限流拒绝（可重试，继续退避）
        assertThatThrownBy(() -> retry.interceptBlocking(REQUEST, MODEL_INFO, rateLimitChain))
                .isInstanceOfSatisfying(LlmRetryExhaustedException.class, e ->
                        assertThat(e.getLastCause()).isInstanceOf(LlmLocalRateLimitException.class));
        assertThat(attempts).hasValue(2);
    }
}
