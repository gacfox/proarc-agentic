package com.gacfox.proarc.agentic.agent;

import com.gacfox.proarc.agentic.agent.interceptor.AgentInterceptor;
import com.gacfox.proarc.agentic.agent.interceptor.AgentInterceptorChain;
import com.gacfox.proarc.agentic.client.LlmClient;
import com.gacfox.proarc.agentic.model.ChatRequest;
import com.gacfox.proarc.agentic.model.openai.*;
import com.gacfox.proarc.agentic.tool.ToolDefinition;
import com.gacfox.proarc.agentic.tool.ToolRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Builder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.util.*;

/**
 * ReAct智能体执行器
 */
@Slf4j
@Builder
public class ReActAgentExecutor {
    private static final String FINAL_ANSWER_TOOL = "final_answer";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final LlmClient defaultLlmClient;
    private final ToolRegistry toolRegistry;
    private final List<String> defaultToolNames;
    private final List<AgentInterceptor> interceptors;
    @Builder.Default
    private final int maxIterations = 50;

    public Flux<AgentResponse> execute(AgentContext context) {
        Sinks.Many<AgentResponse> sink = Sinks.many().unicast().onBackpressureBuffer();
        Schedulers.boundedElastic().schedule(() -> {
            try {
                doExecute(context, sink);
            } catch (Exception e) {
                log.error("Agent execution error", e);
                emitSignal(sink, AgentResponse.error(e.getMessage()));
            } finally {
                sink.tryEmitComplete();
            }
        });
        return sink.asFlux();
    }

    private void doExecute(AgentContext context, Sinks.Many<AgentResponse> sink) {
        context.setMessages(new ArrayList<>(context.getMessages()));
        List<AgentInterceptor> sortedInterceptors = Optional.ofNullable(interceptors)
                .orElseGet(Collections::emptyList)
                .stream()
                .sorted(Comparator.comparingInt(AgentInterceptor::getOrder))
                .toList();

        for (int i = 0; i < this.maxIterations; i++) {
            AgentInterceptorChain chain = new AgentInterceptorChain() {
                int index = 0;

                @Override
                public AgentLoopResult next(AgentContext context) {
                    if (index < sortedInterceptors.size()) {
                        AgentInterceptor interceptor = sortedInterceptors.get(index++);
                        return interceptor.intercept(context, this);
                    }
                    return executeLoop(context, sink);
                }
            };

            AgentLoopResult loopResult = chain.next(context);
            if (loopResult.isFinished() || loopResult.isSuspended()) {
                return;
            }
        }

        emitSignal(sink, AgentResponse.error("Agent reached maximum iterations"));
    }

    private AgentLoopResult executeLoop(AgentContext context, Sinks.Many<AgentResponse> sink) {
        LlmClient llmClient = context.getLlmClient() != null ? context.getLlmClient() : defaultLlmClient;
        List<String> toolNames = context.getToolNames() != null && !context.getToolNames().isEmpty()
                ? context.getToolNames() : defaultToolNames;
        List<ToolDefinition> toolDefs = resolveToolDefinitions(toolNames);
        List<Tool> tools = buildToolList(toolDefs);

        Map<String, ToolDefinition> toolMap = new HashMap<>();
        for (ToolDefinition td : toolDefs) {
            toolMap.put(td.getToolName(), td);
        }

        List<AgentResponse> responses = new ArrayList<>();
        ChatRequest chatRequest = ChatRequest.builder()
                .messages(context.getMessages())
                .temperature(context.getTemperature())
                .enableThinking(context.getEnableThinking())
                .topP(context.getTopP())
                .topK(context.getTopK())
                .presencePenalty(context.getPresencePenalty())
                .frequencyPenalty(context.getFrequencyPenalty())
                .seed(context.getSeed())
                .maxTokens(context.getMaxTokens())
                .tools(tools)
                .toolChoice("auto")
                .build();
        ModelResponse response = context.isStreaming()
                ? streamingChat(llmClient, chatRequest, sink)
                : llmClient.blockingChat(chatRequest);
        if (response.getChoices() == null || response.getChoices().isEmpty()) {
            throw new IllegalStateException("LLM returned an empty response");
        }

        Message assistantMessage = response.getChoices().getFirst().getMessage();
        context.getMessages().add(assistantMessage);

        String thinking = response.extractBlockingReasoningContent();
        if (StringUtils.hasText(thinking)) {
            emit(sink, responses, AgentResponse.thinking(thinking));
        }

        String finishReason = response.extractBlockingFinishReason();
        if (!"tool_calls".equals(finishReason)) {
            repairOrphanedToolCalls(assistantMessage, finishReason, context, sink, responses);
            context.getMessages().add(Message.builder()
                    .role(Message.ROLE_USER)
                    .content("""
                            No tool call was detected.
                            To finish: call final_answer.
                            To continue working: call the required tool immediately.
                            Plain text descriptions of tool usage are not allowed.
                            """)
                    .build());
            return AgentLoopResult.continueWith(responses);
        }

        List<ToolCall> toolCalls = response.extractBlockingToolCalls();
        String finalMessage = null;
        for (ToolCall toolCall : toolCalls) {
            ToolCallFunction fn = toolCall.getFunction();
            String toolName = fn.getName();
            String arguments = fn.getArguments();

            emit(sink, responses, AgentResponse.toolCall(toolCall.getId(), toolName, arguments));

            if (finalMessage != null) {
                String skipped = "Skipped: the agent loop has already ended with final_answer.";
                emit(sink, responses, AgentResponse.toolResult(toolCall.getId(), toolName, skipped));
                context.getMessages().add(toolResultMessage(toolCall.getId(), skipped));
                continue;
            }

            if (FINAL_ANSWER_TOOL.equals(toolName)) {
                finalMessage = extractFinalAnswer(arguments);
                emit(sink, responses, AgentResponse.finalAnswer(finalMessage));
                context.getMessages().add(toolResultMessage(toolCall.getId(), "Final answer submitted."));
                continue;
            }

            String result;
            if (isValidJson(arguments)) {
                result = invokeTool(toolMap, toolName, arguments, context);
            } else {
                fn.setArguments("{}");
                result = "Error: tool arguments are not valid JSON (possibly truncated): " + arguments;
            }
            emit(sink, responses, AgentResponse.toolResult(toolCall.getId(), toolName, result));
            context.getMessages().add(toolResultMessage(toolCall.getId(), result));
        }
        return finalMessage != null
                ? AgentLoopResult.finishWith(responses)
                : AgentLoopResult.continueWith(responses);
    }

    private ModelResponse streamingChat(LlmClient llmClient, ChatRequest chatRequest, Sinks.Many<AgentResponse> sink) {
        StreamingDeltaEmitter emitter = new StreamingDeltaEmitter(sink);
        llmClient.streamingChat(chatRequest)
                .publishOn(Schedulers.boundedElastic())
                .doOnNext(emitter::accept)
                .blockLast();
        return ModelResponse.mergeStreamChunks(emitter.chunks);
    }

    private static void emit(Sinks.Many<AgentResponse> sink, List<AgentResponse> responses, AgentResponse response) {
        responses.add(response);
        emitSignal(sink, response);
    }

    private static void emitSignal(Sinks.Many<AgentResponse> sink, AgentResponse response) {
        while (true) {
            Sinks.EmitResult result = sink.tryEmitNext(response);
            if (result != Sinks.EmitResult.FAIL_NON_SERIALIZED) {
                if (result.isFailure() && result != Sinks.EmitResult.FAIL_CANCELLED
                        && result != Sinks.EmitResult.FAIL_TERMINATED) {
                    log.warn("Emit failed: {} type={}", result, response.getType());
                }
                return;
            }
        }
    }

    /**
     * 流式增量事件发射器：收集chunk用于最终聚合，同时把思考与final_answer的内容增量实时发射为delta事件
     */
    private static final class StreamingDeltaEmitter {
        private final Sinks.Many<AgentResponse> sink;
        private final List<ModelResponse> chunks = new ArrayList<>();
        private final Map<Integer, String> toolNames = new HashMap<>();
        private final Map<Integer, FinalAnswerDeltaExtractor> extractors = new HashMap<>();

        private StreamingDeltaEmitter(Sinks.Many<AgentResponse> sink) {
            this.sink = sink;
        }

        void accept(ModelResponse chunk) {
            chunks.add(chunk);
            if (chunk.getChoices() == null) {
                return;
            }
            for (Choice choice : chunk.getChoices()) {
                if (choice.getIndex() != null && choice.getIndex() != 0) {
                    continue;
                }
                Delta delta = choice.getDelta();
                if (delta == null) {
                    continue;
                }
                if (StringUtils.hasText(delta.getReasoning())) {
                    emitSignal(sink, AgentResponse.thinkingDelta(delta.getReasoning()));
                }
                if (delta.getToolCalls() != null) {
                    for (ToolCall toolCall : delta.getToolCalls()) {
                        int index = toolCall.getIndex() != null ? toolCall.getIndex() : 0;
                        ToolCallFunction fn = toolCall.getFunction();
                        if (fn == null) {
                            continue;
                        }
                        if (fn.getName() != null) {
                            toolNames.put(index, fn.getName());
                        }
                        if (FINAL_ANSWER_TOOL.equals(toolNames.get(index)) && fn.getArguments() != null) {
                            String text = extractors.computeIfAbsent(index, k -> new FinalAnswerDeltaExtractor())
                                    .accept(fn.getArguments());
                            if (!text.isEmpty()) {
                                emitSignal(sink, AgentResponse.finalAnswerDelta(text));
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * final_answer工具参数的增量解析器：从流式拼接的JSON参数中增量解码message字段文本，
     * 容错处理不完整JSON与跨chunk边界的转义序列
     */
    private static final class FinalAnswerDeltaExtractor {
        private final StringBuilder raw = new StringBuilder();
        private int emitted;

        String accept(String fragment) {
            raw.append(fragment);
            String decoded = decodeMessageValue(raw);
            if (decoded.length() <= emitted) {
                return "";
            }
            String delta = decoded.substring(emitted);
            emitted = decoded.length();
            return delta;
        }

        private static String decodeMessageValue(StringBuilder raw) {
            int keyIndex = raw.indexOf("\"message\"");
            if (keyIndex < 0) {
                return "";
            }
            int i = keyIndex + "\"message\"".length();
            while (i < raw.length() && (raw.charAt(i) == ':' || Character.isWhitespace(raw.charAt(i)))) {
                i++;
            }
            if (i >= raw.length() || raw.charAt(i) != '"') {
                return "";
            }
            i++;
            StringBuilder out = new StringBuilder();
            boolean closed = false;
            while (i < raw.length()) {
                char c = raw.charAt(i);
                if (c == '"') {
                    closed = true;
                    break;
                }
                if (c != '\\') {
                    out.append(c);
                    i++;
                    continue;
                }
                if (i + 1 >= raw.length()) {
                    break;
                }
                char esc = raw.charAt(i + 1);
                switch (esc) {
                    case '"', '\\', '/' -> {
                        out.append(esc);
                        i += 2;
                    }
                    case 'n' -> {
                        out.append('\n');
                        i += 2;
                    }
                    case 't' -> {
                        out.append('\t');
                        i += 2;
                    }
                    case 'r' -> {
                        out.append('\r');
                        i += 2;
                    }
                    case 'b' -> {
                        out.append('\b');
                        i += 2;
                    }
                    case 'f' -> {
                        out.append('\f');
                        i += 2;
                    }
                    case 'u' -> {
                        if (i + 5 >= raw.length()) {
                            return out.toString();
                        }
                        int codeUnit;
                        try {
                            codeUnit = Integer.parseInt(raw.substring(i + 2, i + 6), 16);
                        } catch (NumberFormatException e) {
                            return out.toString();
                        }
                        out.append((char) codeUnit);
                        i += 6;
                    }
                    default -> {
                        out.append(esc);
                        i += 2;
                    }
                }
            }
            int length = out.length();
            if (!closed && length > 0 && Character.isHighSurrogate(out.charAt(length - 1))) {
                out.setLength(length - 1);
            }
            return out.toString();
        }
    }

    /**
     * 修复非正常结束响应中残留的孤儿tool_calls，保证消息历史中每个tool_call都有配对的工具结果消息
     */
    private void repairOrphanedToolCalls(Message assistantMessage, String finishReason,
                                         AgentContext context, Sinks.Many<AgentResponse> sink,
                                         List<AgentResponse> responses) {
        List<ToolCall> orphaned = assistantMessage.getToolCalls();
        if (orphaned == null || orphaned.isEmpty()) {
            return;
        }
        if (orphaned.stream().anyMatch(toolCall -> !StringUtils.hasText(toolCall.getId()))) {
            assistantMessage.setToolCalls(null);
            return;
        }
        String note = "Error: tool call was not executed because the response ended with finish_reason='"
                + finishReason + "'.";
        for (ToolCall toolCall : orphaned) {
            ToolCallFunction fn = toolCall.getFunction();
            emit(sink, responses, AgentResponse.toolCall(toolCall.getId(), fn.getName(), fn.getArguments()));
            emit(sink, responses, AgentResponse.toolResult(toolCall.getId(), fn.getName(), note));
            context.getMessages().add(toolResultMessage(toolCall.getId(), note));
        }
    }

    private Message toolResultMessage(String toolCallId, String content) {
        return Message.builder().role(Message.ROLE_TOOL).toolCallId(toolCallId).content(content).build();
    }

    private List<ToolDefinition> resolveToolDefinitions(List<String> toolNames) {
        if (toolNames == null || toolNames.isEmpty()) {
            return List.of();
        }
        List<ToolDefinition> toolDefs = new ArrayList<>(toolNames.size());
        for (String toolName : toolNames) {
            ToolDefinition toolDef = toolRegistry.getAgenticTool(toolName);
            if (toolDef == null) {
                throw new IllegalStateException("Tool not found in registry: '" + toolName + "'");
            }
            toolDefs.add(toolDef);
        }
        return toolDefs;
    }

    private List<Tool> buildToolList(List<ToolDefinition> toolDefs) {
        List<Tool> tools = new ArrayList<>();
        for (ToolDefinition td : toolDefs) {
            try {
                tools.add(OBJECT_MAPPER.readValue(td.getJsonSchema(), Tool.class));
            } catch (Exception e) {
                throw new RuntimeException("Failed to parse tool JSON schema: " + td.getToolName(), e);
            }
        }
        tools.add(Tool.builder().function(
                Function.builder()
                        .name(FINAL_ANSWER_TOOL)
                        .description("Submit the final answer to the user's request and end the agent loop")
                        .parameters(Parameters.builder()
                                .properties(Map.of("message",
                                        Property.builder()
                                                .type("string")
                                                .description("The final answer message")
                                                .build()))
                                .required(List.of("message"))
                                .build())
                        .build()).build());
        return tools;
    }

    /**
     * 校验工具参数是否为合法JSON，截断产生的非法参数若保留在消息历史中会导致后续请求被LLM端点拒绝
     */
    private static boolean isValidJson(String arguments) {
        if (!StringUtils.hasText(arguments)) {
            return false;
        }
        try {
            OBJECT_MAPPER.readTree(arguments);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private String invokeTool(Map<String, ToolDefinition> toolMap, String toolName, String arguments, AgentContext agentContext) {
        ToolDefinition toolDef = toolMap.get(toolName);
        if (toolDef == null) {
            return "Error: tool '" + toolName + "' not found";
        }
        try {
            AgentContext snapshot = agentContext.snapshot();
            String result = toolDef.getInvoker().invoke(arguments, snapshot);
            agentContext.getVariables().putAll(snapshot.getVariables());
            return result;
        } catch (Exception e) {
            log.error("Tool invocation error: {}", toolName, e);
            return "Error: " + e.getMessage();
        }
    }

    private String extractFinalAnswer(String arguments) {
        try {
            JsonNode node = OBJECT_MAPPER.readTree(arguments);
            JsonNode msgNode = node.get("message");
            return msgNode != null ? msgNode.asText() : arguments;
        } catch (Exception e) {
            return arguments;
        }
    }
}
