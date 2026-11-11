package com.gacfox.proarc.agentic.agent;

import com.gacfox.proarc.agentic.client.LlmClient;
import com.gacfox.proarc.agentic.model.ChatRequest;
import com.gacfox.proarc.agentic.model.ReasoningEffort;
import com.gacfox.proarc.agentic.model.UserMessage;
import com.gacfox.proarc.agentic.model.openai.Choice;
import com.gacfox.proarc.agentic.model.openai.Delta;
import com.gacfox.proarc.agentic.model.openai.Message;
import com.gacfox.proarc.agentic.model.openai.ModelInfo;
import com.gacfox.proarc.agentic.model.openai.ModelResponse;
import com.gacfox.proarc.agentic.model.openai.ToolCall;
import com.gacfox.proarc.agentic.model.openai.ToolCallFunction;
import com.gacfox.proarc.agentic.tool.AgenticTool;
import com.gacfox.proarc.agentic.tool.AgenticToolParam;
import com.gacfox.proarc.agentic.tool.ToolRegistry;
import lombok.Data;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ReActAgentExecutorTest {

    @Data
    static class WeatherQuery {
        @AgenticToolParam(name = "city", description = "城市名")
        private String city;
    }

    static class WeatherTools {
        @AgenticTool(name = "query_weather", description = "查询天气")
        public String queryWeather(@AgenticToolParam(name = "query", description = "查询条件") WeatherQuery query) {
            return query.getCity() + "：晴，26℃";
        }

        @AgenticTool(name = "current_time", description = "获取当前时间")
        public String currentTime() {
            return "12:00";
        }
    }

    static class FakeLlmClient implements LlmClient {
        private final Queue<ModelResponse> blockingScript = new ArrayDeque<>();
        private final Queue<Flux<ModelResponse>> streamingScript = new ArrayDeque<>();
        final List<ChatRequest> receivedRequests = new ArrayList<>();

        FakeLlmClient(ModelResponse... responses) {
            blockingScript.addAll(List.of(responses));
        }

        FakeLlmClient enqueueStreaming(Flux<ModelResponse> flux) {
            streamingScript.add(flux);
            return this;
        }

        @Override
        public ModelInfo getModelInfo() {
            return ModelInfo.builder().provider("fake").model("fake").build();
        }

        @Override
        public ModelResponse blockingChat(ChatRequest chatRequest) {
            receivedRequests.add(chatRequest);
            return blockingScript.poll();
        }

        @Override
        public Flux<ModelResponse> streamingChat(ChatRequest chatRequest) {
            receivedRequests.add(chatRequest);
            return streamingScript.poll();
        }
    }

    private ToolRegistry toolRegistry;

    @BeforeEach
    void setUp() {
        toolRegistry = new ToolRegistry();
        toolRegistry.register(new WeatherTools());
    }

    private static ModelResponse toolCallResponse(String id, String name, String arguments) {
        return ModelResponse.builder()
                .choices(List.of(Choice.builder()
                        .index(0)
                        .finishReason("tool_calls")
                        .message(Message.builder()
                                .role(Message.ROLE_ASSISTANT)
                                .toolCalls(List.of(ToolCall.builder()
                                        .id(id)
                                        .function(ToolCallFunction.builder().name(name).arguments(arguments).build())
                                        .build()))
                                .build())
                        .build()))
                .build();
    }

    private static ModelResponse directAnswerResponse(String message) {
        return contentResponse(message, "stop");
    }

    private static ModelResponse contentResponse(String content, String finishReason) {
        return ModelResponse.builder()
                .choices(List.of(Choice.builder()
                        .index(0)
                        .finishReason(finishReason)
                        .message(Message.builder()
                                .role(Message.ROLE_ASSISTANT)
                                .content(content)
                                .build())
                        .build()))
                .build();
    }

    private static ModelResponse finalAnswerResponse(String message) {
        return toolCallResponse("call-final", "final_answer", "{\"message\":\"" + message + "\"}");
    }

    private static AgentContext context(String question) {
        return AgentContext.builder()
                .messages(new ArrayList<>(List.of(new UserMessage(question))))
                .build();
    }

    private static List<AgentResponse> run(ReActAgentExecutor executor, AgentContext context) {
        return executor.execute(context).collectList().block(Duration.ofSeconds(10));
    }

    private ReActAgentExecutor executor(FakeLlmClient client) {
        return ReActAgentExecutor.builder()
                .defaultLlmClient(client)
                .toolRegistry(toolRegistry)
                .defaultToolNames(List.of("query_weather"))
                .build();
    }

    private ReActAgentExecutor executorWithTools(FakeLlmClient client, String... toolNames) {
        return ReActAgentExecutor.builder()
                .defaultLlmClient(client)
                .toolRegistry(toolRegistry)
                .defaultToolNames(List.of(toolNames))
                .build();
    }

    private ReActAgentExecutor forcedExecutor(FakeLlmClient client) {
        return ReActAgentExecutor.builder()
                .defaultLlmClient(client)
                .toolRegistry(toolRegistry)
                .defaultToolNames(List.of("query_weather"))
                .convergenceMode(ConvergenceMode.FINAL_ANSWER)
                .build();
    }

    @Test
    void emptyStringArgumentsForNoArgToolInvokesNormally() {
        FakeLlmClient client = new FakeLlmClient(
                toolCallResponse("call-1", "current_time", ""),
                directAnswerResponse("现在是12点"));

        List<AgentResponse> events = run(executorWithTools(client, "current_time"), context("现在几点？"));

        assertThat(events.get(1).getType()).isEqualTo(AgentResponse.Type.TOOL_RESULT);
        assertThat(events.get(1).getContent()).isEqualTo("12:00");
        assertThat(events).extracting(AgentResponse::getType).endsWith(AgentResponse.Type.COMPLETED);
    }

    @Test
    void nullArgumentsForNoArgToolInvokesNormally() {
        FakeLlmClient client = new FakeLlmClient(
                toolCallResponse("call-1", "current_time", null),
                directAnswerResponse("现在是12点"));

        List<AgentResponse> events = run(executorWithTools(client, "current_time"), context("现在几点？"));

        assertThat(events.get(1).getType()).isEqualTo(AgentResponse.Type.TOOL_RESULT);
        assertThat(events.get(1).getContent()).isEqualTo("12:00");
    }

    @Test
    void blankArgumentsForNoArgToolInvokesNormally() {
        FakeLlmClient client = new FakeLlmClient(
                toolCallResponse("call-1", "current_time", "   "),
                directAnswerResponse("现在是12点"));

        List<AgentResponse> events = run(executorWithTools(client, "current_time"), context("现在几点？"));

        assertThat(events.get(1).getType()).isEqualTo(AgentResponse.Type.TOOL_RESULT);
        assertThat(events.get(1).getContent()).isEqualTo("12:00");
    }

    @Test
    void fullReActLoopInvokesToolAndReturnsFinalAnswer() {
        FakeLlmClient client = new FakeLlmClient(
                toolCallResponse("call-1", "query_weather", "{\"city\":\"北京\"}"),
                directAnswerResponse("北京今天晴，26℃"));

        List<AgentResponse> events = run(executor(client), context("北京天气怎么样？"));

        assertThat(events).extracting(AgentResponse::getType)
                .containsExactly(
                        AgentResponse.Type.TOOL_CALL,
                        AgentResponse.Type.TOOL_RESULT,
                        AgentResponse.Type.CONTENT,
                        AgentResponse.Type.COMPLETED);
        assertThat(events.get(0).getToolName()).isEqualTo("query_weather");
        assertThat(events.get(1).getContent()).isEqualTo("北京：晴，26℃");
        assertThat(events.get(3).getContent()).isEqualTo("北京今天晴，26℃");
    }

    @Test
    void intermediateContentIsEmittedBeforeToolCalls() {
        ModelResponse toolResponse = toolCallResponse("call-1", "query_weather", "{\"city\":\"北京\"}");
        toolResponse.getChoices().getFirst().getMessage().setContent("我先查询天气");
        FakeLlmClient client = new FakeLlmClient(
                toolResponse,
                directAnswerResponse("北京今天晴，26℃"));

        List<AgentResponse> events = run(executor(client), context("北京天气怎么样？"));

        assertThat(events).extracting(AgentResponse::getType)
                .containsExactly(
                        AgentResponse.Type.CONTENT,
                        AgentResponse.Type.TOOL_CALL,
                        AgentResponse.Type.TOOL_RESULT,
                        AgentResponse.Type.CONTENT,
                        AgentResponse.Type.COMPLETED);
        assertThat(events.get(0).getContent()).isEqualTo("我先查询天气");
        assertThat(events.get(3).getContent()).isEqualTo("北京今天晴，26℃");
    }

    @Test
    void toolResultIsAppendedToMessageHistory() {
        FakeLlmClient client = new FakeLlmClient(
                toolCallResponse("call-1", "query_weather", "{\"city\":\"北京\"}"),
                directAnswerResponse("完成"));

        run(executor(client), context("北京天气怎么样？"));

        ChatRequest secondRequest = client.receivedRequests.get(1);
        assertThat(secondRequest.getMessages()).extracting(Message::getRole)
                .startsWith("user", "assistant", "tool");
        Message toolMessage = secondRequest.getMessages().get(2);
        assertThat(toolMessage.getToolCallId()).isEqualTo("call-1");
        assertThat(toolMessage.getContent()).isEqualTo("北京：晴，26℃");
    }

    @Test
    void directContentEndsLoopByDefault() {
        FakeLlmClient client = new FakeLlmClient(directAnswerResponse("直接回答"));

        List<AgentResponse> events = run(executor(client), context("你好"));

        assertThat(events).extracting(AgentResponse::getType)
                .containsExactly(AgentResponse.Type.CONTENT, AgentResponse.Type.COMPLETED);
        assertThat(events.get(0).getContent()).isEqualTo("直接回答");
        assertThat(events.get(1).getContent()).isEqualTo("直接回答");
        assertThat(client.receivedRequests).hasSize(1);
        assertThat(client.receivedRequests.getFirst().getTools())
                .noneMatch(tool -> "final_answer".equals(tool.getFunction().getName()));
        assertThat(client.receivedRequests.getFirst().getToolChoice()).isEqualTo("auto");
    }

    @Test
    void noToolsOmitsToolsAndToolChoice() {
        FakeLlmClient client = new FakeLlmClient(directAnswerResponse("直接回答"));
        ReActAgentExecutor executor = ReActAgentExecutor.builder()
                .defaultLlmClient(client)
                .toolRegistry(toolRegistry)
                .build();

        List<AgentResponse> events = run(executor, context("你好"));

        assertThat(events).extracting(AgentResponse::getType)
                .containsExactly(AgentResponse.Type.CONTENT, AgentResponse.Type.COMPLETED);
        ChatRequest request = client.receivedRequests.getFirst();
        assertThat(request.getTools()).isNull();
        assertThat(request.getToolChoice()).isNull();
    }

    @Test
    void emptyOrBlankContentWithoutToolCallsEmitsError() {
        for (String content : new String[]{null, "", "  \n"}) {
            FakeLlmClient client = new FakeLlmClient(directAnswerResponse(content));

            List<AgentResponse> events = run(executor(client), context("你好"));

            assertThat(events).extracting(AgentResponse::getType).endsWith(AgentResponse.Type.ERROR);
            assertThat(events).noneMatch(event -> event.getType() == AgentResponse.Type.COMPLETED);
            assertThat(events.getLast().getContent()).contains("empty content");
            assertThat(client.receivedRequests).hasSize(1);
        }
    }

    @Test
    void truncatedContentWithoutToolCallsEmitsErrorInsteadOfCompleting() {
        FakeLlmClient client = new FakeLlmClient(contentResponse("未完成的回答", "length"));

        List<AgentResponse> events = run(executor(client), context("你好"));

        assertThat(events).extracting(AgentResponse::getType)
                .containsExactly(AgentResponse.Type.CONTENT, AgentResponse.Type.ERROR);
        assertThat(events.getLast().getContent()).contains("truncated");
        assertThat(client.receivedRequests).hasSize(1);
    }

    @Test
    void abnormalFinishReasonWithoutToolCallsEmitsError() {
        FakeLlmClient client = new FakeLlmClient(contentResponse("不完整的内容", "content_filter"));

        List<AgentResponse> events = run(executor(client), context("你好"));

        assertThat(events).extracting(AgentResponse::getType)
                .containsExactly(AgentResponse.Type.CONTENT, AgentResponse.Type.ERROR);
        assertThat(events.getLast().getContent()).contains("content_filter");
    }

    @Test
    void finalAnswerToolEndsLoopInForcedMode() {
        FakeLlmClient client = new FakeLlmClient(finalAnswerResponse("直接回答"));

        List<AgentResponse> events = run(forcedExecutor(client), context("你好"));

        assertThat(events).extracting(AgentResponse::getType)
                .containsExactly(AgentResponse.Type.TOOL_CALL, AgentResponse.Type.COMPLETED);
        assertThat(events.get(1).getContent()).isEqualTo("直接回答");
        assertThat(client.receivedRequests).hasSize(1);
        assertThat(client.receivedRequests.getFirst().getTools())
                .anyMatch(tool -> "final_answer".equals(tool.getFunction().getName()));
        assertThat(client.receivedRequests.getFirst().getToolChoice()).isEqualTo("auto");
    }

    @Test
    void completedIsEmittedAfterSkippedCallsInForcedMode() {
        ModelResponse finalThenTool = ModelResponse.builder()
                .choices(List.of(Choice.builder()
                        .index(0)
                        .finishReason("tool_calls")
                        .message(Message.builder()
                                .role(Message.ROLE_ASSISTANT)
                                .toolCalls(List.of(
                                        ToolCall.builder()
                                                .id("call-final")
                                                .function(ToolCallFunction.builder()
                                                        .name("final_answer")
                                                        .arguments("{\"message\":\"完成\"}")
                                                        .build())
                                                .build(),
                                        ToolCall.builder()
                                                .id("call-weather")
                                                .function(ToolCallFunction.builder()
                                                        .name("query_weather")
                                                        .arguments("{\"city\":\"北京\"}")
                                                        .build())
                                                .build()))
                                .build())
                        .build()))
                .build();
        FakeLlmClient client = new FakeLlmClient(finalThenTool);

        List<AgentResponse> events = run(forcedExecutor(client), context("测试"));

        assertThat(events).extracting(AgentResponse::getType)
                .containsExactly(
                        AgentResponse.Type.TOOL_CALL,
                        AgentResponse.Type.TOOL_CALL,
                        AgentResponse.Type.TOOL_RESULT,
                        AgentResponse.Type.COMPLETED);
        assertThat(events.get(2).getContent()).startsWith("Skipped:");
        assertThat(events.getLast().getContent()).isEqualTo("完成");
    }

    @Test
    void passesReasoningEffortToLlmRequest() {
        FakeLlmClient client = new FakeLlmClient(directAnswerResponse("完成"));
        AgentContext context = AgentContext.builder()
                .messages(new ArrayList<>(List.of(new UserMessage("你好"))))
                .reasoningEffort(ReasoningEffort.XHIGH)
                .build();

        run(executor(client), context);

        assertThat(client.receivedRequests.getFirst().getReasoningEffort()).isEqualTo("xhigh");
        assertThat(context.snapshot().getReasoningEffort()).isEqualTo("xhigh");
    }

    @Test
    void unknownToolReturnsErrorResultAndContinues() {
        FakeLlmClient client = new FakeLlmClient(
                toolCallResponse("call-1", "nonexistent", "{}"),
                directAnswerResponse("恢复"));

        List<AgentResponse> events = run(executor(client), context("测试"));

        assertThat(events.get(1).getType()).isEqualTo(AgentResponse.Type.TOOL_RESULT);
        assertThat(events.get(1).getContent()).contains("not found");
        assertThat(events).extracting(AgentResponse::getType).endsWith(AgentResponse.Type.COMPLETED);
    }

    @Test
    void invalidJsonArgumentsProduceErrorResult() {
        FakeLlmClient client = new FakeLlmClient(
                toolCallResponse("call-1", "query_weather", "not-json"),
                directAnswerResponse("恢复"));

        List<AgentResponse> events = run(executor(client), context("测试"));

        assertThat(events.get(1).getContent()).startsWith("Error: tool arguments are not valid JSON");
    }

    @Test
    void toolExceptionBecomesErrorResult() {
        ToolRegistry failingRegistry = new ToolRegistry();
        failingRegistry.register(new Object() {
            @AgenticTool(name = "failing_tool", description = "总是失败")
            public String fail(@AgenticToolParam(name = "query", description = "查询") WeatherQuery query) {
                throw new IllegalStateException("boom");
            }
        });
        FakeLlmClient client = new FakeLlmClient(
                toolCallResponse("call-1", "failing_tool", "{\"city\":\"北京\"}"),
                directAnswerResponse("恢复"));

        ReActAgentExecutor executor = ReActAgentExecutor.builder()
                .defaultLlmClient(client)
                .toolRegistry(failingRegistry)
                .defaultToolNames(List.of("failing_tool"))
                .build();

        List<AgentResponse> events = run(executor, context("测试"));

        assertThat(events.get(1).getContent()).startsWith("Error:");
        assertThat(events).extracting(AgentResponse::getType).endsWith(AgentResponse.Type.COMPLETED);
    }

    @Test
    void plainTextResponseTriggersReminderAndContinues() {
        ModelResponse plainText = ModelResponse.builder()
                .choices(List.of(Choice.builder()
                        .index(0)
                        .finishReason("stop")
                        .message(Message.builder().role(Message.ROLE_ASSISTANT).content("我不知道").build())
                        .build()))
                .build();
        FakeLlmClient client = new FakeLlmClient(plainText, finalAnswerResponse("恢复"));

        List<AgentResponse> events = run(forcedExecutor(client), context("测试"));

        assertThat(events).extracting(AgentResponse::getType)
                .containsExactly(
                        AgentResponse.Type.CONTENT,
                        AgentResponse.Type.TOOL_CALL,
                        AgentResponse.Type.COMPLETED);
        assertThat(client.receivedRequests.get(1).getMessages().get(2).getContent().toString())
                .contains("No tool call was detected");
    }

    @Test
    void reachesMaxIterationsEmitsError() {
        FakeLlmClient alwaysToolCall = new FakeLlmClient() {
            @Override
            public ModelResponse blockingChat(ChatRequest chatRequest) {
                return toolCallResponse("call-x", "query_weather", "{\"city\":\"北京\"}");
            }
        };
        ReActAgentExecutor executor = ReActAgentExecutor.builder()
                .defaultLlmClient(alwaysToolCall)
                .toolRegistry(toolRegistry)
                .defaultToolNames(List.of("query_weather"))
                .maxIterations(2)
                .build();

        List<AgentResponse> events = run(executor, context("测试"));

        assertThat(events).extracting(AgentResponse::getType).endsWith(AgentResponse.Type.ERROR);
        assertThat(events.getLast().getContent()).contains("maximum iterations");
    }

    @Test
    void streamingModeEmitsContentDeltasAndCompleted() {
        FakeLlmClient client = new FakeLlmClient();
        ModelResponse chunk1 = ModelResponse.builder()
                .choices(List.of(Choice.builder()
                        .index(0)
                        .delta(Delta.builder()
                                .role("assistant")
                                .content("北京今天晴")
                                .build())
                        .build()))
                .build();
        ModelResponse chunk2 = ModelResponse.builder()
                .choices(List.of(Choice.builder()
                        .index(0)
                        .finishReason("stop")
                        .delta(Delta.builder().content("，26℃").build())
                        .build()))
                .build();
        client.enqueueStreaming(Flux.just(chunk1, chunk2));

        AgentContext ctx = context("北京天气怎么样？");
        ctx.setStreaming(true);

        List<AgentResponse> events = run(executor(client), ctx);

        assertThat(events).extracting(AgentResponse::getType)
                .containsExactly(
                        AgentResponse.Type.CONTENT_DELTA,
                        AgentResponse.Type.CONTENT_DELTA,
                        AgentResponse.Type.CONTENT,
                        AgentResponse.Type.COMPLETED);
        assertThat(events.get(0).getContent()).isEqualTo("北京今天晴");
        assertThat(events.get(1).getContent()).isEqualTo("，26℃");
        assertThat(events.get(2).getContent()).isEqualTo("北京今天晴，26℃");
        assertThat(events.get(3).getContent()).isEqualTo("北京今天晴，26℃");
    }

    @Test
    void streamingTruncatedContentEmitsErrorInsteadOfCompleting() {
        ModelResponse truncatedChunk = ModelResponse.builder()
                .choices(List.of(Choice.builder()
                        .index(0)
                        .finishReason("length")
                        .delta(Delta.builder().role("assistant").content("未完成的回答").build())
                        .build()))
                .build();
        FakeLlmClient client = new FakeLlmClient().enqueueStreaming(Flux.just(truncatedChunk));
        AgentContext ctx = context("你好");
        ctx.setStreaming(true);

        List<AgentResponse> events = run(executor(client), ctx);

        assertThat(events).extracting(AgentResponse::getType)
                .containsExactly(
                        AgentResponse.Type.CONTENT_DELTA,
                        AgentResponse.Type.CONTENT,
                        AgentResponse.Type.ERROR);
        assertThat(events.getLast().getContent()).contains("truncated");
    }

    @Test
    void streamingIntermediateContentUsesTheSameContentEvents() {
        FakeLlmClient client = new FakeLlmClient();
        ModelResponse contentChunk = ModelResponse.builder()
                .choices(List.of(Choice.builder()
                        .index(0)
                        .delta(Delta.builder().role("assistant").content("我先查一下").build())
                        .build()))
                .build();
        ModelResponse toolChunk = ModelResponse.builder()
                .choices(List.of(Choice.builder()
                        .index(0)
                        .finishReason("tool_calls")
                        .delta(Delta.builder()
                                .toolCalls(List.of(ToolCall.builder()
                                        .id("call-1")
                                        .index(0)
                                        .function(ToolCallFunction.builder()
                                                .name("query_weather")
                                                .arguments("{\"city\":\"北京\"}")
                                                .build())
                                        .build()))
                                .build())
                        .build()))
                .build();
        ModelResponse answerChunk = ModelResponse.builder()
                .choices(List.of(Choice.builder()
                        .index(0)
                        .finishReason("stop")
                        .delta(Delta.builder().role("assistant").content("北京晴").build())
                        .build()))
                .build();
        client.enqueueStreaming(Flux.just(contentChunk, toolChunk))
                .enqueueStreaming(Flux.just(answerChunk));

        AgentContext ctx = context("北京天气怎么样？");
        ctx.setStreaming(true);
        List<AgentResponse> events = run(executor(client), ctx);

        assertThat(events).extracting(AgentResponse::getType)
                .containsExactly(
                        AgentResponse.Type.CONTENT_DELTA,
                        AgentResponse.Type.CONTENT,
                        AgentResponse.Type.TOOL_CALL,
                        AgentResponse.Type.TOOL_RESULT,
                        AgentResponse.Type.CONTENT_DELTA,
                        AgentResponse.Type.CONTENT,
                        AgentResponse.Type.COMPLETED);
        assertThat(events.get(0).getContent()).isEqualTo("我先查一下");
        assertThat(events.get(1).getContent()).isEqualTo("我先查一下");
        assertThat(events.getLast().getContent()).isEqualTo("北京晴");
    }

    @Test
    void emptyResponseEmitsErrorEvent() {
        FakeLlmClient client = new FakeLlmClient(ModelResponse.builder().build());

        List<AgentResponse> events = run(executor(client), context("测试"));

        assertThat(events).extracting(AgentResponse::getType).endsWith(AgentResponse.Type.ERROR);
        assertThat(events.getLast().getContent()).contains("empty response");
    }

    @Test
    void reasoningContentIsStrippedFromMessageHistory() {
        ModelResponse thinkingResponse = ModelResponse.builder()
                .choices(List.of(Choice.builder()
                        .index(0)
                        .finishReason("tool_calls")
                        .message(Message.builder()
                                .role(Message.ROLE_ASSISTANT)
                                .reasoningContent("让我思考一下天气")
                                .toolCalls(List.of(ToolCall.builder()
                                        .id("call-1")
                                        .function(ToolCallFunction.builder()
                                                .name("query_weather")
                                                .arguments("{\"city\":\"北京\"}")
                                                .build())
                                        .build()))
                                .build())
                        .build()))
                .build();
        FakeLlmClient client = new FakeLlmClient(thinkingResponse, directAnswerResponse("北京晴"));

        List<AgentResponse> events = run(executor(client), context("北京天气怎么样？"));

        assertThat(events).extracting(AgentResponse::getType).contains(AgentResponse.Type.THINKING);
        Message assistantInHistory = client.receivedRequests.get(1).getMessages().stream()
                .filter(m -> Message.ROLE_ASSISTANT.equals(m.getRole()))
                .findFirst()
                .orElseThrow();
        assertThat(assistantInHistory.getReasoningContent()).isNull();
    }

    @Test
    void cancelDuringLoopStopsLlmCalls() throws Exception {
        AtomicInteger llmCalls = new AtomicInteger();
        CountDownLatch firstToolResultSeen = new CountDownLatch(1);
        FakeLlmClient endless = new FakeLlmClient() {
            @Override
            public ModelResponse blockingChat(ChatRequest chatRequest) {
                int n = llmCalls.incrementAndGet();
                return toolCallResponse("call-" + n, "query_weather", "{\"city\":\"北京\"}");
            }
        };

        Disposable subscription = executor(endless).execute(context("测试"))
                .doOnNext(e -> {
                    if (e.getType() == AgentResponse.Type.TOOL_RESULT) {
                        firstToolResultSeen.countDown();
                    }
                })
                .subscribe();

        assertThat(firstToolResultSeen.await(5, TimeUnit.SECONDS)).isTrue();
        subscription.dispose();

        int callsAtCancel = llmCalls.get();
        Thread.sleep(300);
        // 允许一次在途竞态，之后LLM调用必须停止增长
        assertThat(llmCalls.get()).isLessThanOrEqualTo(callsAtCancel + 1);
    }

    @Test
    void cancelDuringStreamingCancelsUpstreamFlux() throws Exception {
        AtomicBoolean upstreamCancelled = new AtomicBoolean();
        CountDownLatch firstDeltaSeen = new CountDownLatch(1);

        ModelResponse reasoningChunk = ModelResponse.builder()
                .choices(List.of(Choice.builder()
                        .index(0)
                        .delta(Delta.builder().role("assistant").reasoning("思考中").build())
                        .build()))
                .build();
        Flux<ModelResponse> endlessStream = Flux.<ModelResponse>generate(s -> s.next(reasoningChunk))
                .doOnCancel(() -> upstreamCancelled.set(true));

        FakeLlmClient client = new FakeLlmClient().enqueueStreaming(endlessStream);
        AgentContext ctx = context("测试");
        ctx.setStreaming(true);

        Disposable subscription = executor(client).execute(ctx)
                .doOnNext(e -> firstDeltaSeen.countDown())
                .subscribe();

        assertThat(firstDeltaSeen.await(5, TimeUnit.SECONDS)).isTrue();
        subscription.dispose();

        long deadline = System.currentTimeMillis() + 5000;
        while (!upstreamCancelled.get() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertThat(upstreamCancelled.get()).isTrue();
    }

    static class StopAwareTools {
        final AtomicBoolean weatherExecuted = new AtomicBoolean();

        @AgenticTool(name = "stopper", description = "请求停止执行")
        public String stopper(AgentContext context) {
            context.requestStop();
            return "stop requested";
        }

        @AgenticTool(name = "spy_weather", description = "查询天气")
        public String spyWeather(@AgenticToolParam(name = "query", description = "查询条件") WeatherQuery query) {
            weatherExecuted.set(true);
            return "晴";
        }
    }

    @Test
    void stopRequestedBeforeExecuteSkipsLlmAndEmitsStopped() {
        FakeLlmClient client = new FakeLlmClient(directAnswerResponse("不应到达"));
        AgentContext ctx = context("测试");
        ctx.requestStop();

        List<AgentResponse> events = run(executor(client), ctx);

        assertThat(events).hasSize(1);
        assertThat(events.getFirst().getType()).isEqualTo(AgentResponse.Type.STOPPED);
        assertThat(client.receivedRequests).isEmpty();
    }

    @Test
    void stopRequestedDuringToolLoopPairsRemainingToolCalls() {
        StopAwareTools tools = new StopAwareTools();
        toolRegistry.register(tools);
        ModelResponse twoCalls = ModelResponse.builder()
                .choices(List.of(Choice.builder()
                        .index(0)
                        .finishReason("tool_calls")
                        .message(Message.builder()
                                .role(Message.ROLE_ASSISTANT)
                                .toolCalls(List.of(
                                        ToolCall.builder()
                                                .id("call-1")
                                                .function(ToolCallFunction.builder().name("stopper").arguments("{}").build())
                                                .build(),
                                        ToolCall.builder()
                                                .id("call-2")
                                                .function(ToolCallFunction.builder().name("spy_weather").arguments("{\"city\":\"北京\"}").build())
                                                .build()))
                                .build())
                        .build()))
                .build();
        FakeLlmClient client = new FakeLlmClient(twoCalls, directAnswerResponse("不应到达"));
        AgentContext ctx = context("测试");

        List<AgentResponse> events = run(executorWithTools(client, "stopper", "spy_weather"), ctx);

        assertThat(tools.weatherExecuted).isFalse();
        assertThat(client.receivedRequests).hasSize(1);
        assertThat(events.getLast().getType()).isEqualTo(AgentResponse.Type.STOPPED);
        assertThat(events).anyMatch(e -> e.getType() == AgentResponse.Type.TOOL_RESULT
                && "call-2".equals(e.getToolCallId()) && e.getContent().startsWith("Cancelled:"));
        long toolResultMessages = ctx.getMessages().stream()
                .filter(m -> Message.ROLE_TOOL.equals(m.getRole()))
                .count();
        assertThat(toolResultMessages).isEqualTo(2);
    }

    @Test
    void stopRequestedDuringStreamingEmitsStoppedAndCompletes() throws Exception {
        CountDownLatch firstDeltaSeen = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(1);
        ModelResponse reasoningChunk = ModelResponse.builder()
                .choices(List.of(Choice.builder()
                        .index(0)
                        .delta(Delta.builder().role("assistant").reasoning("思考中").build())
                        .build()))
                .build();
        Flux<ModelResponse> endlessStream = Flux.<ModelResponse>generate(s -> s.next(reasoningChunk));

        FakeLlmClient client = new FakeLlmClient().enqueueStreaming(endlessStream);
        AgentContext ctx = context("测试");
        ctx.setStreaming(true);

        List<AgentResponse> events = new ArrayList<>();
        executor(client).execute(ctx)
                .doOnNext(e -> {
                    events.add(e);
                    firstDeltaSeen.countDown();
                })
                .doOnComplete(done::countDown)
                .subscribe();

        assertThat(firstDeltaSeen.await(5, TimeUnit.SECONDS)).isTrue();
        ctx.requestStop();

        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(events).anyMatch(e -> e.getType() == AgentResponse.Type.STOPPED);
        assertThat(client.receivedRequests).hasSize(1);
    }
}
