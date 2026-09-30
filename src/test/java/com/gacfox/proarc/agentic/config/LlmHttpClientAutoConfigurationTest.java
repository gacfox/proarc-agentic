package com.gacfox.proarc.agentic.config;

import org.junit.jupiter.api.Test;
import reactor.netty.http.client.HttpClient;

import static org.assertj.core.api.Assertions.assertThat;

class LlmHttpClientAutoConfigurationTest {

    private final LlmHttpClientAutoConfiguration autoConfiguration = new LlmHttpClientAutoConfiguration();

    @Test
    void allowInsecureDefaultsToFalse() {
        assertThat(new LlmHttpClientProperties().isAllowInsecure()).isFalse();
    }

    @Test
    void buildsClientWithDefaultSecureSsl() {
        HttpClient client = autoConfiguration.llmHttpClient(new LlmHttpClientProperties());
        assertThat(client).isNotNull();
    }

    @Test
    void buildsClientWithAllowInsecure() {
        LlmHttpClientProperties props = new LlmHttpClientProperties();
        props.setAllowInsecure(true);

        HttpClient client = autoConfiguration.llmHttpClient(props);
        assertThat(client).isNotNull();
    }
}
