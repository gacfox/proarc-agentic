package com.gacfox.proarc.agentic.model.openai;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ModelResponseTest {

    private static ModelResponse chunk(String role, String content, String reasoning,
                                       List<ToolCall> toolCalls, String finishReason, Usage usage) {
        Delta delta = Delta.builder()
                .role(role)
                .content(content)
                .reasoning(reasoning)
                .toolCalls(toolCalls)
                .build();
        return ModelResponse.builder()
                .id("id-1")
                .created(1)
                .model("m")
                .choices(List.of(Choice.builder().index(0).delta(delta).finishReason(finishReason).build()))
                .usage(usage)
                .build();
    }

    private static ToolCall toolCallPart(String id, Integer index, String name, String arguments) {
        return ToolCall.builder()
                .id(id)
                .index(index)
                .function(ToolCallFunction.builder().name(name).arguments(arguments).build())
                .build();
    }

    @Test
    void mergesContentAndReasoningInOrder() {
        ModelResponse merged = ModelResponse.mergeStreamChunks(List.of(
                chunk("assistant", "Hello", null, null, null, null),
                chunk(null, " world", "思考", null, null, null),
                chunk(null, "!", null, null, "stop", Usage.builder().promptTokens(1).completionTokens(2).totalTokens(3).build())
        ));

        assertThat(merged.getId()).isEqualTo("id-1");
        assertThat(merged.getObject()).isEqualTo("chat.completion");
        assertThat(merged.extractBlockingContent()).isEqualTo("Hello world!");
        assertThat(merged.extractBlockingReasoningContent()).isEqualTo("思考");
        assertThat(merged.extractBlockingFinishReason()).isEqualTo("stop");
        assertThat(merged.getUsage().getTotalTokens()).isEqualTo(3);
        assertThat(merged.getChoices().getFirst().getMessage().getRole()).isEqualTo("assistant");
    }

    @Test
    void mergesToolCallsByIndex() {
        ModelResponse merged = ModelResponse.mergeStreamChunks(List.of(
                chunk("assistant", null, null, List.of(toolCallPart("t1", 0, "query_weather", "{\"ci")), null, null),
                chunk(null, null, null, List.of(toolCallPart(null, 0, null, "ty\":\"北京\"}")), null, null),
                chunk(null, null, null, null, "tool_calls", null)
        ));

        List<ToolCall> toolCalls = merged.extractBlockingToolCalls();
        assertThat(toolCalls).hasSize(1);
        assertThat(toolCalls.getFirst().getId()).isEqualTo("t1");
        assertThat(toolCalls.getFirst().getFunction().getName()).isEqualTo("query_weather");
        assertThat(toolCalls.getFirst().getFunction().getArguments()).isEqualTo("{\"city\":\"北京\"}");
    }

    @Test
    void keepsMultipleToolCallsOrderedByIndex() {
        ModelResponse merged = ModelResponse.mergeStreamChunks(List.of(
                chunk("assistant", null, null, List.of(
                        toolCallPart("t1", 0, "tool_a", "{}"),
                        toolCallPart("t2", 1, "tool_b", "{}")
                ), "tool_calls", null)
        ));

        List<ToolCall> toolCalls = merged.extractBlockingToolCalls();
        assertThat(toolCalls).extracting(tc -> tc.getFunction().getName())
                .containsExactly("tool_a", "tool_b");
    }

    @Test
    void generatesPlaceholderIdWhenToolCallIdMissing() {
        ModelResponse merged = ModelResponse.mergeStreamChunks(List.of(
                chunk("assistant", null, null, List.of(
                        toolCallPart(null, 0, "tool_a", "{}"),
                        toolCallPart("", 1, "tool_b", "{}")
                ), "tool_calls", null)
        ));

        List<ToolCall> toolCalls = merged.extractBlockingToolCalls();
        assertThat(toolCalls).hasSize(2);
        assertThat(toolCalls).allSatisfy(tc -> assertThat(tc.getId()).isNotBlank());
        assertThat(toolCalls.get(0).getId()).isNotEqualTo(toolCalls.get(1).getId());
    }

    @Test
    void aggregatesToolCallFragmentsWhenIndexMissing() {
        ModelResponse merged = ModelResponse.mergeStreamChunks(List.of(
                chunk("assistant", null, null, List.of(toolCallPart("t1", null, "query_weather", "{\"ci")), null, null),
                chunk(null, null, null, List.of(toolCallPart(null, null, null, "ty\":\"北京\"}")), null, null),
                chunk(null, null, null, List.of(toolCallPart(null, null, null, "")), "tool_calls", null)
        ));

        List<ToolCall> toolCalls = merged.extractBlockingToolCalls();
        assertThat(toolCalls).hasSize(1);
        assertThat(toolCalls.getFirst().getId()).isEqualTo("t1");
        assertThat(toolCalls.getFirst().getFunction().getName()).isEqualTo("query_weather");
        assertThat(toolCalls.getFirst().getFunction().getArguments()).isEqualTo("{\"city\":\"北京\"}");
    }

    @Test
    void separatesToolCallsWhenIndexMissingButNewCallStarts() {
        ModelResponse merged = ModelResponse.mergeStreamChunks(List.of(
                chunk("assistant", null, null, List.of(toolCallPart(null, null, "tool_a", "{\"a\":")), null, null),
                chunk(null, null, null, List.of(toolCallPart(null, null, null, "1}")), null, null),
                chunk(null, null, null, List.of(toolCallPart(null, null, "tool_b", "{\"b\":")), null, null),
                chunk(null, null, null, List.of(toolCallPart(null, null, null, "2}")), "tool_calls", null)
        ));

        List<ToolCall> toolCalls = merged.extractBlockingToolCalls();
        assertThat(toolCalls).hasSize(2);
        assertThat(toolCalls.get(0).getFunction().getName()).isEqualTo("tool_a");
        assertThat(toolCalls.get(0).getFunction().getArguments()).isEqualTo("{\"a\":1}");
        assertThat(toolCalls.get(1).getFunction().getName()).isEqualTo("tool_b");
        assertThat(toolCalls.get(1).getFunction().getArguments()).isEqualTo("{\"b\":2}");
    }

    @Test
    void placeholderIdsAreDeterministicAcrossMerges() {
        List<ModelResponse> chunks = List.of(
                chunk("assistant", null, null, List.of(
                        toolCallPart(null, 0, "tool_a", "{}"),
                        toolCallPart("", 1, "tool_b", "{}")
                ), "tool_calls", null)
        );

        ModelResponse first = ModelResponse.mergeStreamChunks(chunks);
        ModelResponse second = ModelResponse.mergeStreamChunks(chunks);

        assertThat(first.extractBlockingToolCalls())
                .extracting(ToolCall::getId)
                .containsExactlyElementsOf(second.extractBlockingToolCalls().stream()
                        .map(ToolCall::getId)
                        .toList());
    }

    @Test
    void keepsChoiceWithFinishReasonOnly() {
        ModelResponse merged = ModelResponse.mergeStreamChunks(List.of(
                chunk("assistant", "部分回复", null, null, null, null),
                chunk(null, null, null, null, "content_filter", null)
        ));

        assertThat(merged.getChoices()).hasSize(1);
        assertThat(merged.extractBlockingContent()).isEqualTo("部分回复");
        assertThat(merged.extractBlockingFinishReason()).isEqualTo("content_filter");
    }

    @Test
    void skipsNullChunks() {
        List<ModelResponse> chunks = new ArrayList<>();
        chunks.add(null);
        chunks.add(chunk("assistant", "hi", null, null, "stop", null));

        ModelResponse merged = ModelResponse.mergeStreamChunks(chunks);
        assertThat(merged.extractBlockingContent()).isEqualTo("hi");
    }

    @Test
    void emptyChoicesReturnNullAndEmpty() {
        ModelResponse empty = ModelResponse.builder().build();

        assertThat(empty.extractBlockingContent()).isNull();
        assertThat(empty.extractBlockingReasoningContent()).isNull();
        assertThat(empty.extractBlockingToolCalls()).isEmpty();
        assertThat(empty.extractBlockingFinishReason()).isNull();
    }

    @Test
    void defaultsRoleToAssistant() {
        ModelResponse merged = ModelResponse.mergeStreamChunks(List.of(
                chunk(null, "hi", null, null, "stop", null)
        ));
        assertThat(merged.getChoices().getFirst().getMessage().getRole()).isEqualTo(Message.ROLE_ASSISTANT);
    }
}
