package com.gacfox.proarc.agentic.prompt;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PromptTemplateTest {

    @Test
    void rendersVariables() {
        String result = PromptTemplate.build("你好，{{name}}！今天是{{day}}。", Map.of("name", "世界", "day", "周一"));
        assertThat(result).isEqualTo("你好，世界！今天是周一。");
    }

    @Test
    void missingParamRendersEmpty() {
        String result = PromptTemplate.build("你好，{{name}}！", Map.of());
        assertThat(result).isEqualTo("你好，！");
    }

    @Test
    void rendersListSection() {
        String result = PromptTemplate.build("工具：{{#items}}{{.}} {{/items}}", Map.of("items", List.of("a", "b", "c")));
        assertThat(result).isEqualTo("工具：a b c ");
    }

    @Test
    void escapesHtmlByDefault() {
        String result = PromptTemplate.build("{{v}}", Map.of("v", "<b>"));
        assertThat(result).isEqualTo("&lt;b&gt;");
    }

    @Test
    void tripleMustacheDoesNotEscape() {
        String result = PromptTemplate.build("{{{v}}}", Map.of("v", "<b>"));
        assertThat(result).isEqualTo("<b>");
    }
}
