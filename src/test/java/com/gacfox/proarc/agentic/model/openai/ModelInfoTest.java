package com.gacfox.proarc.agentic.model.openai;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ModelInfoTest {

    @Test
    void toStringDoesNotExposeSecrets() {
        ModelInfo info = ModelInfo.builder()
                .provider("openai")
                .model("qwen-plus")
                .endpoint("https://example.com/v1/chat/completions")
                .sk("secret-key-123")
                .headers(Map.of("Authorization", "Bearer abc"))
                .build();

        String toString = info.toString();
        assertThat(toString).doesNotContain("secret-key-123");
        assertThat(toString).doesNotContain("abc");
        assertThat(toString).contains("openai", "qwen-plus", "https://example.com");
    }
}
