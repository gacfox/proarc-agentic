package com.gacfox.proarc.agentic.client.interceptor.builtin;

import com.gacfox.proarc.agentic.client.interceptor.LlmInterceptorChain;
import com.gacfox.proarc.agentic.exception.LlmConcurrencyLimitException;
import com.gacfox.proarc.agentic.model.openai.ModelInfo;
import com.gacfox.proarc.agentic.model.openai.ModelRequest;
import com.gacfox.proarc.agentic.model.openai.ModelResponse;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalConcurrencyLimitInterceptorTest {
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
    void rejectsNonPositiveConcurrency() {
        assertThatThrownBy(() -> new LocalConcurrencyLimitInterceptor(0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void blockingAcquiresAndReleasesPermit() {
        LocalConcurrencyLimitInterceptor interceptor = new LocalConcurrencyLimitInterceptor(1);

        assertThat(interceptor.interceptBlocking(REQUEST, MODEL_INFO, stubChain())).isSameAs(RESPONSE);
        assertThat(interceptor.interceptBlocking(REQUEST, MODEL_INFO, stubChain())).isSameAs(RESPONSE);
    }

    @Test
    void blockingExhaustedThrows() {
        LocalConcurrencyLimitInterceptor interceptor = new LocalConcurrencyLimitInterceptor(1);

        LlmInterceptorChain reentrantChain = new LlmInterceptorChain() {
            @Override
            public ModelResponse nextBlocking(ModelRequest request) {
                return interceptor.interceptBlocking(request, MODEL_INFO, stubChain());
            }

            @Override
            public Flux<ModelResponse> nextStreaming(ModelRequest request) {
                throw new UnsupportedOperationException();
            }
        };

        assertThatThrownBy(() -> interceptor.interceptBlocking(REQUEST, MODEL_INFO, reentrantChain))
                .isInstanceOf(LlmConcurrencyLimitException.class)
                .hasMessageContaining("maxConcurrency=1");
    }

    @Test
    void blockingReleasesPermitOnException() {
        LocalConcurrencyLimitInterceptor interceptor = new LocalConcurrencyLimitInterceptor(1);
        LlmInterceptorChain failingChain = new LlmInterceptorChain() {
            @Override
            public ModelResponse nextBlocking(ModelRequest request) {
                throw new RuntimeException("boom");
            }

            @Override
            public Flux<ModelResponse> nextStreaming(ModelRequest request) {
                throw new UnsupportedOperationException();
            }
        };

        assertThatThrownBy(() -> interceptor.interceptBlocking(REQUEST, MODEL_INFO, failingChain))
                .isInstanceOf(RuntimeException.class);
        assertThat(interceptor.interceptBlocking(REQUEST, MODEL_INFO, stubChain())).isSameAs(RESPONSE);
    }

    @Test
    void streamingHoldsPermitUntilTermination() {
        LocalConcurrencyLimitInterceptor interceptor = new LocalConcurrencyLimitInterceptor(1);
        LlmInterceptorChain neverChain = new LlmInterceptorChain() {
            @Override
            public ModelResponse nextBlocking(ModelRequest request) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Flux<ModelResponse> nextStreaming(ModelRequest request) {
                return Flux.never();
            }
        };

        Disposable subscription = interceptor.interceptStreaming(REQUEST, MODEL_INFO, neverChain).subscribe();

        assertThatThrownBy(() -> interceptor.interceptBlocking(REQUEST, MODEL_INFO, stubChain()))
                .isInstanceOf(LlmConcurrencyLimitException.class);

        subscription.dispose();
        assertThat(interceptor.interceptBlocking(REQUEST, MODEL_INFO, stubChain())).isSameAs(RESPONSE);
    }

    @Test
    void assemblyWithoutSubscriptionDoesNotConsumePermit() {
        LocalConcurrencyLimitInterceptor interceptor = new LocalConcurrencyLimitInterceptor(1);

        interceptor.interceptStreaming(REQUEST, MODEL_INFO, stubChain());
        interceptor.interceptStreaming(REQUEST, MODEL_INFO, stubChain());

        assertThat(interceptor.interceptBlocking(REQUEST, MODEL_INFO, stubChain())).isSameAs(RESPONSE);
    }

    @Test
    void resubscriptionAcquiresAndReleasesPermitPerSubscription() {
        LocalConcurrencyLimitInterceptor interceptor = new LocalConcurrencyLimitInterceptor(1);

        Flux<ModelResponse> flux = interceptor.interceptStreaming(REQUEST, MODEL_INFO, stubChain());
        StepVerifier.create(flux).expectNext(RESPONSE).verifyComplete();
        StepVerifier.create(flux).expectNext(RESPONSE).verifyComplete();

        assertThat(interceptor.interceptBlocking(REQUEST, MODEL_INFO, stubChain())).isSameAs(RESPONSE);
    }

    @Test
    void streamingExhaustedReturnsErrorFlux() {
        LocalConcurrencyLimitInterceptor interceptor = new LocalConcurrencyLimitInterceptor(1);
        LlmInterceptorChain neverChain = new LlmInterceptorChain() {
            @Override
            public ModelResponse nextBlocking(ModelRequest request) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Flux<ModelResponse> nextStreaming(ModelRequest request) {
                return Flux.never();
            }
        };
        Disposable held = interceptor.interceptStreaming(REQUEST, MODEL_INFO, neverChain).subscribe();
        try {
            StepVerifier.create(interceptor.interceptStreaming(REQUEST, MODEL_INFO, stubChain()))
                    .expectError(LlmConcurrencyLimitException.class)
                    .verify();
        } finally {
            held.dispose();
        }
    }

    @Test
    void orderIsMinus110() {
        assertThat(new LocalConcurrencyLimitInterceptor(1).getOrder()).isEqualTo(-110);
    }
}
