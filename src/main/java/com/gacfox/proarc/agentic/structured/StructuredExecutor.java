package com.gacfox.proarc.agentic.structured;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gacfox.proarc.agentic.client.LlmClient;
import com.gacfox.proarc.agentic.model.ChatRequest;
import com.gacfox.proarc.agentic.model.SystemMessage;
import com.gacfox.proarc.agentic.model.openai.Message;
import com.gacfox.proarc.agentic.model.openai.ModelResponse;
import com.gacfox.proarc.agentic.model.openai.Tool;
import com.gacfox.proarc.agentic.model.openai.ToolCall;
import com.gacfox.proarc.agentic.model.openai.ToolCallFunction;
import com.gacfox.proarc.agentic.schema.AgenticSchemaBuilder;
import lombok.RequiredArgsConstructor;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 结构化输出执行器
 */
@RequiredArgsConstructor
public class StructuredExecutor {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private final AgenticSchemaBuilder schemaBuilder = new AgenticSchemaBuilder();

    private final LlmClient llmClient;

    /**
     * 执行结构化输出
     *
     * @param request 结构化输出请求
     * @param <T>     结构化输出类型
     * @return 结构化输出响应
     */
    public <T> StructuredResponse<T> execute(StructuredChatRequest<T> request) {
        validateRequest(request);

        String toolName = StringUtils.hasText(request.getToolName())
                ? request.getToolName()
                : "structured_output";
        Tool tool = schemaBuilder.buildTool(toolName, buildToolDescription(request.getResponseType()), request.getResponseType());
        ModelResponse modelResponse = llmClient.blockingChat(buildChatRequest(request, tool, toolName));
        ToolCall toolCall = extractToolCall(modelResponse, toolName);
        String arguments = toolCall.getFunction().getArguments();
        T result = readResult(arguments, request.getResponseType());
        return new StructuredResponse<>(result, arguments, modelResponse, toolCall);
    }

    private <T> void validateRequest(StructuredChatRequest<T> request) {
        if (request == null) {
            throw new IllegalArgumentException("Structured chat request must not be null");
        }
        if (CollectionUtils.isEmpty(request.getMessages())) {
            throw new IllegalArgumentException("Structured chat request messages must not be empty");
        }
        if (request.getResponseType() == null) {
            throw new IllegalArgumentException("Structured chat request responseType must not be null");
        }
    }

    private <T> ChatRequest buildChatRequest(StructuredChatRequest<T> request, Tool tool, String toolName) {
        List<Message> messages = buildMessages(request.getMessages(), request.getInstruction(), toolName);

        return ChatRequest.builder()
                .messages(messages)
                .temperature(request.getTemperature())
                .enableThinking(request.getEnableThinking())
                .topP(request.getTopP())
                .topK(request.getTopK())
                .presencePenalty(request.getPresencePenalty())
                .frequencyPenalty(request.getFrequencyPenalty())
                .seed(request.getSeed())
                .maxTokens(request.getMaxTokens())
                .tools(List.of(tool))
                .toolChoice(Map.of(
                        "type", "function",
                        "function", Map.of("name", toolName)
                ))
                .build();
    }

    private List<Message> buildMessages(List<Message> originalMessages, String instruction, String toolName) {
        List<Message> messages = new ArrayList<>(originalMessages.size() + 1);
        boolean hasSystemMessage = false;
        for (Message message : originalMessages) {
            if (Message.ROLE_SYSTEM.equals(message.getRole())) {
                hasSystemMessage = true;
            }
            messages.add(message);
        }

        if (!hasSystemMessage) {
            messages.addFirst(new SystemMessage(buildInstruction(instruction, toolName)));
        }
        return messages;
    }

    private String buildInstruction(String instruction, String toolName) {
        String defaultInstruction = """
                You must extract the answer as structured data.
                Always call the %s tool exactly once.
                Do not answer with plain text.
                If a value is unknown, use null when the field type allows it.
                """.formatted(toolName);
        if (!StringUtils.hasText(instruction)) {
            return defaultInstruction;
        }
        return defaultInstruction + "\n" + instruction;
    }

    private String buildToolDescription(Class<?> responseType) {
        return "Return the structured output for " + responseType.getSimpleName();
    }

    private ToolCall extractToolCall(ModelResponse response, String toolName) {
        List<ToolCall> toolCalls = response != null ? response.extractBlockingToolCalls() : List.of();
        for (ToolCall toolCall : toolCalls) {
            ToolCallFunction function = toolCall.getFunction();
            if (function != null && toolName.equals(function.getName())) {
                if (!StringUtils.hasText(function.getArguments())) {
                    throw new IllegalStateException("Structured tool call arguments are empty: " + toolName);
                }
                return toolCall;
            }
        }
        throw new IllegalStateException("Structured tool call not found: " + toolName);
    }

    private <T> T readResult(String arguments, Class<T> responseType) {
        try {
            return OBJECT_MAPPER.readValue(arguments, responseType);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse structured output: " + responseType.getName(), e);
        }
    }
}
