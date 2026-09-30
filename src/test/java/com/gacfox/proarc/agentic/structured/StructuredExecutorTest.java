package com.gacfox.proarc.agentic.structured;

import com.gacfox.proarc.agentic.client.LlmClient;
import com.gacfox.proarc.agentic.model.ChatRequest;
import com.gacfox.proarc.agentic.model.SystemMessage;
import com.gacfox.proarc.agentic.model.UserMessage;
import com.gacfox.proarc.agentic.model.openai.Choice;
import com.gacfox.proarc.agentic.model.openai.Message;
import com.gacfox.proarc.agentic.model.openai.ModelResponse;
import com.gacfox.proarc.agentic.model.openai.ToolCall;
import com.gacfox.proarc.agentic.model.openai.ToolCallFunction;
import com.gacfox.proarc.agentic.tool.AgenticToolParam;
import lombok.Data;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StructuredExecutorTest {

    @Data
    static class Person {
        @AgenticToolParam(name = "name", description = "姓名")
        private String name;
        @AgenticToolParam(name = "age", description = "年龄", required = false)
        private Integer age;
    }

    @Mock
    private LlmClient llmClient;

    private StructuredExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new StructuredExecutor(llmClient);
    }

    private static ModelResponse responseWithToolCall(String toolName, String arguments) {
        return ModelResponse.builder()
                .choices(List.of(Choice.builder()
                        .index(0)
                        .message(Message.builder()
                                .role(Message.ROLE_ASSISTANT)
                                .toolCalls(List.of(ToolCall.builder()
                                        .id("call-1")
                                        .function(ToolCallFunction.builder()
                                                .name(toolName)
                                                .arguments(arguments)
                                                .build())
                                        .build()))
                                .build())
                        .build()))
                .build();
    }

    private static StructuredChatRequest<Person> personRequest() {
        return StructuredChatRequest.<Person>builder()
                .messages(List.of(new UserMessage("提取人物信息")))
                .responseType(Person.class)
                .build();
    }

    @Test
    void deserializesToolCallArgumentsIntoResult() {
        when(llmClient.blockingChat(any()))
                .thenReturn(responseWithToolCall("structured_output", "{\"name\":\"Tom\",\"age\":3}"));

        StructuredResponse<Person> response = executor.execute(personRequest());

        assertThat(response.extract().getName()).isEqualTo("Tom");
        assertThat(response.extract().getAge()).isEqualTo(3);
        assertThat(response.getArguments()).contains("Tom");
        assertThat(response.getToolCall().getId()).isEqualTo("call-1");
        assertThat(response.getRawResponse()).isNotNull();
    }

    @Test
    void injectsSystemInstructionWhenAbsent() {
        when(llmClient.blockingChat(any()))
                .thenReturn(responseWithToolCall("structured_output", "{\"name\":\"Tom\"}"));

        executor.execute(personRequest());

        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(llmClient).blockingChat(captor.capture());
        ChatRequest sent = captor.getValue();
        assertThat(sent.getMessages()).hasSize(2);
        assertThat(sent.getMessages().getFirst().getRole()).isEqualTo(Message.ROLE_SYSTEM);
        assertThat((String) sent.getMessages().getFirst().getContent()).contains("structured_output");
        assertThat(sent.getTools()).hasSize(1);
        assertThat(sent.getToolChoice()).isNotNull();
    }

    @Test
    void keepsExistingSystemMessage() {
        when(llmClient.blockingChat(any()))
                .thenReturn(responseWithToolCall("structured_output", "{\"name\":\"Tom\"}"));

        StructuredChatRequest<Person> request = personRequest();
        request.setMessages(List.of(new SystemMessage("自定义系统提示"), new UserMessage("提取")));

        executor.execute(request);

        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(llmClient).blockingChat(captor.capture());
        assertThat(captor.getValue().getMessages()).hasSize(2);
        assertThat(captor.getValue().getMessages().getFirst().getContent()).isEqualTo("自定义系统提示");
    }

    @Test
    void supportsCustomToolName() {
        when(llmClient.blockingChat(any()))
                .thenReturn(responseWithToolCall("extract_person", "{\"name\":\"Tom\"}"));

        StructuredChatRequest<Person> request = personRequest();
        request.setToolName("extract_person");

        StructuredResponse<Person> response = executor.execute(request);
        assertThat(response.extract().getName()).isEqualTo("Tom");
    }

    @Test
    void throwsWhenToolCallNotFound() {
        when(llmClient.blockingChat(any()))
                .thenReturn(responseWithToolCall("other_tool", "{\"name\":\"Tom\"}"));

        assertThatThrownBy(() -> executor.execute(personRequest()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void throwsWhenToolCallArgumentsEmpty() {
        when(llmClient.blockingChat(any()))
                .thenReturn(responseWithToolCall("structured_output", ""));

        assertThatThrownBy(() -> executor.execute(personRequest()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("empty");
    }

    @Test
    void throwsWhenArgumentsNotValidJson() {
        when(llmClient.blockingChat(any()))
                .thenReturn(responseWithToolCall("structured_output", "not-json"));

        assertThatThrownBy(() -> executor.execute(personRequest()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Failed to parse");
    }

    @Test
    void validatesRequest() {
        assertThatThrownBy(() -> executor.execute(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> executor.execute(StructuredChatRequest.<Person>builder()
                .messages(List.of()).responseType(Person.class).build()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> executor.execute(StructuredChatRequest.<Person>builder()
                .messages(List.of(new UserMessage("hi"))).build()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
