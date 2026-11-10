package com.gacfox.proarc.agentic.model.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UsageTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void readsCachedTokensFromPromptTokensDetails() throws Exception {
        Usage usage = objectMapper.readValue("""
                {
                  "prompt_tokens": 100,
                  "completion_tokens": 20,
                  "total_tokens": 120,
                  "prompt_tokens_details": {"cached_tokens": 64}
                }
                """, Usage.class);

        assertThat(usage.getCachedTokens()).isEqualTo(64);
        assertThat(usage.getPromptTokensDetails().getCachedTokens()).isEqualTo(64);
    }

    @Test
    void keepsCachedTokensNullWhenProviderDoesNotReportIt() throws Exception {
        Usage usage = objectMapper.readValue("""
                {"prompt_tokens": 100, "completion_tokens": 20, "total_tokens": 120}
                """, Usage.class);

        assertThat(usage.getCachedTokens()).isNull();
    }
}
