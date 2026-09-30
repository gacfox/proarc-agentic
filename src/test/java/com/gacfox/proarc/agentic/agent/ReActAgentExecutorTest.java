package com.gacfox.proarc.agentic.agent;

import com.gacfox.proarc.agentic.client.LlmClient;
import com.gacfox.proarc.agentic.model.ChatRequest;
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

    @Test
    void emptyStringArgumentsForNoArgToolInvokesNormally() {
        FakeLlmClient client = new FakeLlmClient(
                toolCallResponse("call-1", "current_time", ""),
                finalAnswerResponse("现在是12点"));

        List<AgentResponse> events = run(executorWithTools(client, "current_time"), context("现在几点？"));

        assertThat(events.get(1).getType()).isEqualTo(AgentResponse.Type.TOOL_RESULT);
        assertThat(events.get(1).getContent()).isEqualTo("12:00");
        assertThat(events).extracting(AgentResponse::getType).endsWith(AgentResponse.Type.FINAL_ANSWER);
    }

    @Test
    void nullArgumentsForNoArgToolInvokesNormally() {
        FakeLlmClient client = new FakeLlmClient(
                toolCallResponse("call-1", "current_time", null),
                finalAnswerResponse("现在是12点"));

        List<AgentResponse> events = run(executorWithTools(client, "current_time"), context("现在几点？"));

        assertThat(events.get(1).getType()).isEqualTo(AgentResponse.Type.TOOL_RESULT);
        assertThat(events.get(1).getContent()).isEqualTo("12:00");
    }

    @Test
    void blankArgumentsForNoArgToolInvokesNormally() {
        FakeLlmClient client = new FakeLlmClient(
                toolCallResponse("call-1", "current_time", "   "),
                finalAnswerResponse("现在是12点"));

        List<AgentResponse> events = run(executorWithTools(client, "current_time"), context("现在几点？"));

        assertThat(events.get(1).getType()).isEqualTo(AgentResponse.Type.TOOL_RESULT);
        assertThat(events.get(1).getContent()).isEqualTo("12:00");
    }

    @Test
    void fullReActLoopInvokesToolAndReturnsFinalAnswer() {
        FakeLlmClient client = new FakeLlmClient(
                toolCallResponse("call-1", "query_weather", "{\"city\":\"北京\"}"),
                finalAnswerResponse("北京今天晴，26℃"));

        List<AgentResponse> events = run(executor(client), context("北京天气怎么样？"));

        assertThat(events).extracting(AgentResponse::getType)
                .containsExactly(
                        AgentResponse.Type.TOOL_CALL,
                        AgentResponse.Type.TOOL_RESULT,
                        AgentResponse.Type.TOOL_CALL,
                        AgentResponse.Type.FINAL_ANSWER);
        assertThat(events.get(0).getToolName()).isEqualTo("query_weather");
        assertThat(events.get(1).getContent()).isEqualTo("北京：晴，26℃");
        assertThat(events.get(3).getContent()).isEqualTo("北京今天晴，26℃");
    }

    @Test
    void toolResultIsAppendedToMessageHistory() {
        FakeLlmClient client = new FakeLlmClient(
                toolCallResponse("call-1", "query_weather", "{\"city\":\"北京\"}"),
                finalAnswerResponse("完成"));

        run(executor(client), context("北京天气怎么样？"));

        ChatRequest secondRequest = client.receivedRequests.get(1);
        assertThat(secondRequest.getMessages()).extracting(Message::getRole)
                .startsWith("user", "assistant", "tool");
        Message toolMessage = secondRequest.getMessages().get(2);
        assertThat(toolMessage.getToolCallId()).isEqualTo("call-1");
        assertThat(toolMessage.getContent()).isEqualTo("北京：晴，26℃");
    }

    @Test
    void directFinalAnswerEndsLoop() {
        FakeLlmClient client = new FakeLlmClient(finalAnswerResponse("直接回答"));

        List<AgentResponse> events = run(executor(client), context("你好"));

        assertThat(events).extracting(AgentResponse::getType)
                .containsExactly(AgentResponse.Type.TOOL_CALL, AgentResponse.Type.FINAL_ANSWER);
        assertThat(client.receivedRequests).hasSize(1);
    }

    @Test
    void unknownToolReturnsErrorResultAndContinues() {
        FakeLlmClient client = new FakeLlmClient(
                toolCallResponse("call-1", "nonexistent", "{}"),
                finalAnswerResponse("恢复"));

        List<AgentResponse> events = run(executor(client), context("测试"));

        assertThat(events.get(1).getType()).isEqualTo(AgentResponse.Type.TOOL_RESULT);
        assertThat(events.get(1).getContent()).contains("not found");
        assertThat(events).extracting(AgentResponse::getType).endsWith(AgentResponse.Type.FINAL_ANSWER);
    }

    @Test
    void invalidJsonArgumentsProduceErrorResult() {
        FakeLlmClient client = new FakeLlmClient(
                toolCallResponse("call-1", "query_weather", "not-json"),
                finalAnswerResponse("恢复"));

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
                finalAnswerResponse("恢复"));

        ReActAgentExecutor executor = ReActAgentExecutor.builder()
                .defaultLlmClient(client)
                .toolRegistry(failingRegistry)
                .defaultToolNames(List.of("failing_tool"))
                .build();

        List<AgentResponse> events = run(executor, context("测试"));

        assertThat(events.get(1).getContent()).startsWith("Error:");
        assertThat(events).extracting(AgentResponse::getType).endsWith(AgentResponse.Type.FINAL_ANSWER);
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

        List<AgentResponse> events = run(executor(client), context("测试"));

        assertThat(events).extracting(AgentResponse::getType).endsWith(AgentResponse.Type.FINAL_ANSWER);
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
    void streamingModeEmitsFinalAnswerDeltas() {
        FakeLlmClient client = new FakeLlmClient();
        ModelResponse chunk1 = ModelResponse.builder()
                .choices(List.of(Choice.builder()
                        .index(0)
                        .delta(Delta.builder()
                                .role("assistant")
                                .toolCalls(List.of(ToolCall.builder()
                                        .id("c1")
                                        .index(0)
                                        .function(ToolCallFunction.builder()
                                                .name("final_answer")
                                                .arguments("{\"message\":\"北京今天晴")
                                                .build())
                                        .build()))
                                .build())
                        .build()))
                .build();
        ModelResponse chunk2 = ModelResponse.builder()
                .choices(List.of(Choice.builder()
                        .index(0)
                        .finishReason("tool_calls")
                        .delta(Delta.builder()
                                .toolCalls(List.of(ToolCall.builder()
                                        .index(0)
                                        .function(ToolCallFunction.builder().arguments("\"}").build())
                                        .build()))
                                .build())
                        .build()))
                .build();
        client.enqueueStreaming(Flux.just(chunk1, chunk2));

        AgentContext ctx = context("北京天气怎么样？");
        ctx.setStreaming(true);

        List<AgentResponse> events = run(executor(client), ctx);

        assertThat(events).extracting(AgentResponse::getType)
                .containsExactly(
                        AgentResponse.Type.FINAL_ANSWER_DELTA,
                        AgentResponse.Type.TOOL_CALL,
                        AgentResponse.Type.FINAL_ANSWER);
        assertThat(events.get(0).getContent()).isEqualTo("北京今天晴");
        assertThat(events.get(2).getContent()).isEqualTo("北京今天晴");
    }

    @Test
    void emptyResponseEmitsErrorEvent() {
        FakeLlmClient client = new FakeLlmClient(ModelResponse.builder().build());

        List<AgentResponse> events = run(executor(client), context("测试"));

        assertThat(events).extracting(AgentResponse::getType).endsWith(AgentResponse.Type.ERROR);
        assertThat(events.getLast().getContent()).contains("empty response");
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
}
