package com.gacfox.proarc.agentic.client.interceptor.builtin;

import com.gacfox.proarc.agentic.client.interceptor.LlmInterceptorChain;
import com.gacfox.proarc.agentic.exception.LlmLocalRateLimitException;
import com.gacfox.proarc.agentic.model.openai.ModelInfo;
import com.gacfox.proarc.agentic.model.openai.ModelRequest;
import com.gacfox.proarc.agentic.model.openai.ModelResponse;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalRateLimiterInterceptorTest {
    private static final ModelInfo MODEL_INFO = ModelInfo.builder().provider("p").model("m").build();
    private static final ModelRequest REQUEST = ModelRequest.builder().build();
    private static final ModelResponse RESPONSE = ModelResponse.builder().build();

    private static LlmInterceptorChain stubChain() {
        return new LlmInterceptorChain() {
            @Override
            public ModelResponse nextBlocking(ModelRequest request) {
                return RESPONSE;
            }

            @Override
            public Flux<ModelResponse> nextStreaming(ModelRequest request) {
                return Flux.just(RESPONSE);
            }
        };
    }

    @Test
    void rejectsNonPositiveQps() {
        assertThatThrownBy(() -> new LocalRateLimiterInterceptor(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LocalRateLimiterInterceptor(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void allowsUpToMaxQpsThenBlocks() {
        LocalRateLimiterInterceptor interceptor = new LocalRateLimiterInterceptor(2);

        assertThat(interceptor.interceptBlocking(REQUEST, MODEL_INFO, stubChain())).isSameAs(RESPONSE);
        assertThat(interceptor.interceptBlocking(REQUEST, MODEL_INFO, stubChain())).isSameAs(RESPONSE);
        assertThatThrownBy(() -> interceptor.interceptBlocking(REQUEST, MODEL_INFO, stubChain()))
                .isInstanceOf(LlmLocalRateLimitException.class)
                .hasMessageContaining("maxQps=2");
    }

    @Test
    void refillsTokensOverTime() throws InterruptedException {
        LocalRateLimiterInterceptor interceptor = new LocalRateLimiterInterceptor(20);

        for (int i = 0; i < 20; i++) {
            interceptor.interceptBlocking(REQUEST, MODEL_INFO, stubChain());
        }
        assertThatThrownBy(() -> interceptor.interceptBlocking(REQUEST, MODEL_INFO, stubChain()))
                .isInstanceOf(LlmLocalRateLimitException.class);

        Thread.sleep(150);
        assertThat(interceptor.interceptBlocking(REQUEST, MODEL_INFO, stubChain())).isSameAs(RESPONSE);
    }

    @Test
    void streamingExceedsLimitReturnsErrorFlux() {
        LocalRateLimiterInterceptor interceptor = new LocalRateLimiterInterceptor(1);

        StepVerifier.create(interceptor.interceptStreaming(REQUEST, MODEL_INFO, stubChain()))
                .expectNext(RESPONSE)
                .verifyComplete();
        StepVerifier.create(interceptor.interceptStreaming(REQUEST, MODEL_INFO, stubChain()))
                .expectError(LlmLocalRateLimitException.class)
                .verify();
    }

    @Test
    void assemblyWithoutSubscriptionDoesNotConsumeToken() {
        LocalRateLimiterInterceptor interceptor = new LocalRateLimiterInterceptor(1);

        interceptor.interceptStreaming(REQUEST, MODEL_INFO, stubChain());
        interceptor.interceptStreaming(REQUEST, MODEL_INFO, stubChain());

        assertThat(interceptor.interceptBlocking(REQUEST, MODEL_INFO, stubChain())).isSameAs(RESPONSE);
    }

    @Test
    void resubscriptionConsumesTokenPerSubscription() {
        LocalRateLimiterInterceptor interceptor = new LocalRateLimiterInterceptor(2);

        Flux<ModelResponse> flux = interceptor.interceptStreaming(REQUEST, MODEL_INFO, stubChain());
        StepVerifier.create(flux).expectNext(RESPONSE).verifyComplete();
        StepVerifier.create(flux).expectNext(RESPONSE).verifyComplete();
        StepVerifier.create(flux).expectError(LlmLocalRateLimitException.class).verify();
    }

    @Test
    void rateLimitExceptionIsRetryable() {
        LocalRateLimiterInterceptor interceptor = new LocalRateLimiterInterceptor(1);
        interceptor.interceptBlocking(REQUEST, MODEL_INFO, stubChain());

        assertThatThrownBy(() -> interceptor.interceptBlocking(REQUEST, MODEL_INFO, stubChain()))
                .isInstanceOfSatisfying(LlmLocalRateLimitException.class, e -> {
                    assertThat(e.isRetryable()).isTrue();
                    assertThat(e.getProvider()).isEqualTo("p");
                    assertThat(e.getModel()).isEqualTo("m");
                });
    }

    @Test
    void orderIsMinus100() {
        assertThat(new LocalRateLimiterInterceptor(1).getOrder()).isEqualTo(-100);
    }
}
