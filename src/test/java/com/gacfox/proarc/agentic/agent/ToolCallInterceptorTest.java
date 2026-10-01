package com.gacfox.proarc.agentic.agent;

import com.gacfox.proarc.agentic.agent.interceptor.ToolCallInterceptor;
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
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ToolCallInterceptorTest {

    @Data
    static class WeatherQuery {
        @AgenticToolParam(name = "city", description = "城市名")
        private String city;
    }

    static class SpyTools {
        final AtomicInteger weatherExecutions = new AtomicInteger();
        final AtomicInteger timeExecutions = new AtomicInteger();
        final List<String> receivedCities = new ArrayList<>();

        @AgenticTool(name = "spy_weather", description = "查询天气")
        public String spyWeather(@AgenticToolParam(name = "query", description = "查询条件") WeatherQuery query) {
            weatherExecutions.incrementAndGet();
            receivedCities.add(query.getCity());
            return query.getCity() + "：晴，26℃";
        }

        @AgenticTool(name = "spy_time", description = "获取当前时间")
        public String spyTime() {
            timeExecutions.incrementAndGet();
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
    private SpyTools spyTools;

    @BeforeEach
    void setUp() {
        spyTools = new SpyTools();
        toolRegistry = new ToolRegistry();
        toolRegistry.register(spyTools);
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

    private static ModelResponse twoToolCallsResponse() {
        return ModelResponse.builder()
                .choices(List.of(Choice.builder()
                        .index(0)
                        .finishReason("tool_calls")
                        .message(Message.builder()
                                .role(Message.ROLE_ASSISTANT)
                                .toolCalls(List.of(
                                        ToolCall.builder()
                                                .id("call-1")
                                                .function(ToolCallFunction.builder()
                                                        .name("spy_weather").arguments("{\"city\":\"北京\"}").build())
                                                .build(),
                                        ToolCall.builder()
                                                .id("call-2")
                                                .function(ToolCallFunction.builder()
                                                        .name("spy_time").arguments("{}").build())
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

    private ReActAgentExecutor executor(FakeLlmClient client, ToolCallInterceptor... interceptors) {
        return ReActAgentExecutor.builder()
                .defaultLlmClient(client)
                .toolRegistry(toolRegistry)
                .defaultToolNames(List.of("spy_weather", "spy_time"))
                .toolCallInterceptors(List.of(interceptors))
                .build();
    }

    @Test
    void interceptorRewritesArgumentsBeforeProceed() {
        FakeLlmClient client = new FakeLlmClient(
                toolCallResponse("call-1", "spy_weather", "{\"city\":\"北京\"}"),
                finalAnswerResponse("完成"));

        List<AgentResponse> events = run(executor(client, (invocation, chain) -> {
            invocation.setArguments("{\"city\":\"上海\"}");
            return chain.proceed(invocation);
        }), context("测试"));

        assertThat(spyTools.receivedCities).containsExactly("上海");
        assertThat(events.get(1).getContent()).isEqualTo("上海：晴，26℃");
    }

    @Test
    void interceptorReplacesResultWithoutProceed() {
        FakeLlmClient client = new FakeLlmClient(
                toolCallResponse("call-1", "spy_weather", "{\"city\":\"北京\"}"),
                finalAnswerResponse("完成"));

        List<AgentResponse> events = run(executor(client, (invocation, chain) -> "MOCKED"), context("测试"));

        assertThat(spyTools.weatherExecutions).hasValue(0);
        assertThat(events.get(1).getType()).isEqualTo(AgentResponse.Type.TOOL_RESULT);
        assertThat(events.get(1).getContent()).isEqualTo("MOCKED");
        Message toolMessage = client.receivedRequests.get(1).getMessages().stream()
                .filter(m -> Message.ROLE_TOOL.equals(m.getRole()))
                .findFirst()
                .orElseThrow();
        assertThat(toolMessage.getContent()).isEqualTo("MOCKED");
    }

    @Test
    void interceptorRejectsToolCallAndLoopContinues() {
        FakeLlmClient client = new FakeLlmClient(
                toolCallResponse("call-1", "spy_weather", "{\"city\":\"北京\"}"),
                finalAnswerResponse("恢复"));

        List<AgentResponse> events = run(executor(client,
                (invocation, chain) -> "Error: tool call rejected by reviewer."), context("测试"));

        assertThat(spyTools.weatherExecutions).hasValue(0);
        assertThat(events.get(1).getContent()).isEqualTo("Error: tool call rejected by reviewer.");
        assertThat(events).extracting(AgentResponse::getType).endsWith(AgentResponse.Type.FINAL_ANSWER);
    }

    @Test
    void interceptorExceptionBecomesErrorResult() {
        FakeLlmClient client = new FakeLlmClient(
                toolCallResponse("call-1", "spy_weather", "{\"city\":\"北京\"}"),
                finalAnswerResponse("恢复"));

        List<AgentResponse> events = run(executor(client, (invocation, chain) -> {
            throw new IllegalStateException("拦截器故障");
        }), context("测试"));

        assertThat(spyTools.weatherExecutions).hasValue(0);
        assertThat(events.get(1).getContent()).startsWith("Error:");
        assertThat(events).extracting(AgentResponse::getType).endsWith(AgentResponse.Type.FINAL_ANSWER);
    }

    @Test
    void interceptorsExecuteInOrder() {
        FakeLlmClient client = new FakeLlmClient(
                toolCallResponse("call-1", "spy_weather", "{\"city\":\"北京\"}"),
                finalAnswerResponse("完成"));
        List<String> trace = new ArrayList<>();
        ToolCallInterceptor second = new ToolCallInterceptor() {
            @Override
            public String intercept(ToolInvocation invocation, com.gacfox.proarc.agentic.agent.interceptor.ToolCallChain chain) throws Exception {
                trace.add("second-before");
                String result = chain.proceed(invocation);
                trace.add("second-after");
                return result;
            }

            @Override
            public int getOrder() {
                return 2;
            }
        };
        ToolCallInterceptor first = new ToolCallInterceptor() {
            @Override
            public String intercept(ToolInvocation invocation, com.gacfox.proarc.agentic.agent.interceptor.ToolCallChain chain) throws Exception {
                trace.add("first-before");
                String result = chain.proceed(invocation);
                trace.add("first-after");
                return result;
            }

            @Override
            public int getOrder() {
                return 1;
            }
        };

        run(executor(client, second, first), context("测试"));

        assertThat(trace).startsWith("first-before", "second-before")
                .endsWith("second-after", "first-after");
    }

    @Test
    void finalAnswerPassesThroughChainAndCanBeRewritten() {
        FakeLlmClient client = new FakeLlmClient(finalAnswerResponse("原始答案"));
        List<String> interceptedToolNames = new ArrayList<>();

        List<AgentResponse> events = run(executor(client, (invocation, chain) -> {
            interceptedToolNames.add(invocation.getToolName());
            return chain.proceed(invocation) + "（已审核）";
        }), context("测试"));

        assertThat(interceptedToolNames).containsExactly("final_answer");
        assertThat(events).extracting(AgentResponse::getType)
                .containsExactly(AgentResponse.Type.TOOL_CALL, AgentResponse.Type.FINAL_ANSWER);
        assertThat(events.get(1).getContent()).isEqualTo("原始答案（已审核）");
    }

    @Test
    void suspendEmitsSuspendedEventAndLeavesToolCallPending() {
        FakeLlmClient client = new FakeLlmClient(
                twoToolCallsResponse(),
                finalAnswerResponse("不应到达"));
        AgentContext ctx = context("测试");

        List<AgentResponse> events = run(executor(client, (invocation, chain) -> {
            if ("spy_time".equals(invocation.getToolName())) {
                throw new AgentSuspendException("awaiting approval: " + invocation.getToolCallId());
            }
            return chain.proceed(invocation);
        }), ctx);

        assertThat(events).extracting(AgentResponse::getType)
                .containsExactly(
                        AgentResponse.Type.TOOL_CALL,
                        AgentResponse.Type.TOOL_RESULT,
                        AgentResponse.Type.TOOL_CALL,
                        AgentResponse.Type.SUSPENDED);
        assertThat(events.getLast().getContent()).contains("awaiting approval");
        assertThat(spyTools.weatherExecutions).hasValue(1);
        assertThat(spyTools.timeExecutions).hasValue(0);
        assertThat(client.receivedRequests).hasSize(1);
        long toolResults = ctx.getMessages().stream()
                .filter(m -> Message.ROLE_TOOL.equals(m.getRole()))
                .count();
        assertThat(toolResults).isEqualTo(1);
    }

    @Test
    void resumeAfterSuspendDrainsPendingToolCallsAndContinues() {
        FakeLlmClient client = new FakeLlmClient(
                toolCallResponse("call-1", "spy_weather", "{\"city\":\"北京\"}"),
                finalAnswerResponse("北京晴"));
        ToolCallInterceptor approval = (invocation, chain) -> {
            if ("final_answer".equals(invocation.getToolName())) {
                return chain.proceed(invocation);
            }
            Object decision = invocation.getAgentContext().getVariables().get("approval:" + invocation.getToolCallId());
            if (decision == null) {
                throw new AgentSuspendException("awaiting approval: " + invocation.getToolCallId());
            }
            return Boolean.TRUE.equals(decision)
                    ? chain.proceed(invocation)
                    : "Error: tool call rejected by reviewer.";
        };
        AgentContext ctx = context("北京天气怎么样？");

        List<AgentResponse> firstRun = run(executor(client, approval), ctx);
        assertThat(firstRun).extracting(AgentResponse::getType).endsWith(AgentResponse.Type.SUSPENDED);
        assertThat(spyTools.weatherExecutions).hasValue(0);

        ctx.getVariables().put("approval:call-1", true);
        List<AgentResponse> secondRun = run(executor(client, approval), ctx);

        assertThat(secondRun).extracting(AgentResponse::getType)
                .containsExactly(
                        AgentResponse.Type.TOOL_CALL,
                        AgentResponse.Type.TOOL_RESULT,
                        AgentResponse.Type.TOOL_CALL,
                        AgentResponse.Type.FINAL_ANSWER);
        assertThat(secondRun.get(1).getContent()).isEqualTo("北京：晴，26℃");
        assertThat(spyTools.weatherExecutions).hasValue(1);
        Message toolMessage = client.receivedRequests.get(1).getMessages().stream()
                .filter(m -> Message.ROLE_TOOL.equals(m.getRole()))
                .findFirst()
                .orElseThrow();
        assertThat(toolMessage.getToolCallId()).isEqualTo("call-1");
    }

    @Test
    void resumeWithRejectionRecordsRejectionResultAndContinues() {
        FakeLlmClient client = new FakeLlmClient(
                toolCallResponse("call-1", "spy_weather", "{\"city\":\"北京\"}"),
                finalAnswerResponse("好吧"));
        ToolCallInterceptor approval = (invocation, chain) -> {
            if ("final_answer".equals(invocation.getToolName())) {
                return chain.proceed(invocation);
            }
            Object decision = invocation.getAgentContext().getVariables().get("approval:" + invocation.getToolCallId());
            if (decision == null) {
                throw new AgentSuspendException("awaiting approval");
            }
            return Boolean.TRUE.equals(decision)
                    ? chain.proceed(invocation)
                    : "Error: tool call rejected by reviewer.";
        };
        AgentContext ctx = context("测试");

        run(executor(client, approval), ctx);
        ctx.getVariables().put("approval:call-1", false);
        List<AgentResponse> secondRun = run(executor(client, approval), ctx);

        assertThat(spyTools.weatherExecutions).hasValue(0);
        assertThat(secondRun.get(1).getType()).isEqualTo(AgentResponse.Type.TOOL_RESULT);
        assertThat(secondRun.get(1).getContent()).contains("rejected");
        assertThat(secondRun).extracting(AgentResponse::getType).endsWith(AgentResponse.Type.FINAL_ANSWER);
    }

    @Test
    void resumeSkipsAlreadyExecutedToolCalls() {
        FakeLlmClient client = new FakeLlmClient(
                twoToolCallsResponse(),
                finalAnswerResponse("完成"));
        ToolCallInterceptor approval = (invocation, chain) -> {
            if ("spy_time".equals(invocation.getToolName())
                    && invocation.getAgentContext().getVariables().get("approval:call-2") == null) {
                throw new AgentSuspendException("awaiting approval: call-2");
            }
            return chain.proceed(invocation);
        };
        AgentContext ctx = context("测试");

        run(executor(client, approval), ctx);
        assertThat(spyTools.weatherExecutions).hasValue(1);

        ctx.getVariables().put("approval:call-2", true);
        List<AgentResponse> secondRun = run(executor(client, approval), ctx);

        assertThat(spyTools.weatherExecutions).hasValue(1);
        assertThat(spyTools.timeExecutions).hasValue(1);
        assertThat(secondRun).extracting(AgentResponse::getType)
                .containsExactly(
                        AgentResponse.Type.TOOL_CALL,
                        AgentResponse.Type.TOOL_RESULT,
                        AgentResponse.Type.TOOL_CALL,
                        AgentResponse.Type.FINAL_ANSWER);
        assertThat(secondRun.get(0).getToolCallId()).isEqualTo("call-2");
    }

    @Test
    void noDrainWhenTrailingUserMessageFollowsPendingToolCalls() {
        Message assistant = Message.builder()
                .role(Message.ROLE_ASSISTANT)
                .toolCalls(List.of(ToolCall.builder()
                        .id("call-1")
                        .function(ToolCallFunction.builder().name("spy_weather").arguments("{\"city\":\"北京\"}").build())
                        .build()))
                .build();
        AgentContext ctx = AgentContext.builder()
                .messages(new ArrayList<>(List.of(
                        new UserMessage("北京天气"),
                        assistant,
                        new UserMessage("算了，换个问题"))))
                .build();
        FakeLlmClient client = new FakeLlmClient(finalAnswerResponse("好的"));

        List<AgentResponse> events = run(executor(client, (invocation, chain) -> chain.proceed(invocation)), ctx);

        assertThat(spyTools.weatherExecutions).hasValue(0);
        assertThat(events).extracting(AgentResponse::getType)
                .containsExactly(AgentResponse.Type.TOOL_CALL, AgentResponse.Type.FINAL_ANSWER);
    }

    @Test
    void drainExecutesFinalAnswerAndEndsLoop() {
        Message assistant = Message.builder()
                .role(Message.ROLE_ASSISTANT)
                .toolCalls(List.of(
                        ToolCall.builder()
                                .id("call-1")
                                .function(ToolCallFunction.builder().name("spy_weather").arguments("{\"city\":\"北京\"}").build())
                                .build(),
                        ToolCall.builder()
                                .id("call-2")
                                .function(ToolCallFunction.builder().name("final_answer").arguments("{\"message\":\"北京晴\"}").build())
                                .build()))
                .build();
        AgentContext ctx = AgentContext.builder()
                .messages(new ArrayList<>(List.of(
                        new UserMessage("北京天气"),
                        assistant,
                        Message.builder().role(Message.ROLE_TOOL).toolCallId("call-1").content("北京：晴，26℃").build())))
                .build();
        FakeLlmClient client = new FakeLlmClient();

        List<AgentResponse> events = run(executor(client, (invocation, chain) -> chain.proceed(invocation)), ctx);

        assertThat(events).extracting(AgentResponse::getType)
                .containsExactly(AgentResponse.Type.TOOL_CALL, AgentResponse.Type.FINAL_ANSWER);
        assertThat(events.get(1).getContent()).isEqualTo("北京晴");
        assertThat(client.receivedRequests).isEmpty();
    }

    @Test
    void suspendAndResumeInStreamingMode() {
        ModelResponse toolChunk = ModelResponse.builder()
                .choices(List.of(Choice.builder()
                        .index(0)
                        .finishReason("tool_calls")
                        .delta(Delta.builder()
                                .role("assistant")
                                .toolCalls(List.of(ToolCall.builder()
                                        .id("call-1")
                                        .index(0)
                                        .function(ToolCallFunction.builder()
                                                .name("spy_weather").arguments("{\"city\":\"北京\"}").build())
                                        .build()))
                                .build())
                        .build()))
                .build();
        ModelResponse answerChunk = ModelResponse.builder()
                .choices(List.of(Choice.builder()
                        .index(0)
                        .finishReason("tool_calls")
                        .delta(Delta.builder()
                                .toolCalls(List.of(ToolCall.builder()
                                        .id("call-final")
                                        .index(0)
                                        .function(ToolCallFunction.builder()
                                                .name("final_answer").arguments("{\"message\":\"北京晴\"}").build())
                                        .build()))
                                .build())
                        .build()))
                .build();
        FakeLlmClient client = new FakeLlmClient()
                .enqueueStreaming(Flux.just(toolChunk))
                .enqueueStreaming(Flux.just(answerChunk));
        ToolCallInterceptor approval = (invocation, chain) -> {
            if ("spy_weather".equals(invocation.getToolName())
                    && invocation.getAgentContext().getVariables().get("approval:call-1") == null) {
                throw new AgentSuspendException("awaiting approval: call-1");
            }
            return chain.proceed(invocation);
        };
        AgentContext ctx = context("北京天气怎么样？");
        ctx.setStreaming(true);

        List<AgentResponse> firstRun = run(executor(client, approval), ctx);
        assertThat(firstRun).extracting(AgentResponse::getType).endsWith(AgentResponse.Type.SUSPENDED);
        assertThat(spyTools.weatherExecutions).hasValue(0);

        ctx.getVariables().put("approval:call-1", true);
        List<AgentResponse> secondRun = run(executor(client, approval), ctx);

        assertThat(spyTools.weatherExecutions).hasValue(1);
        assertThat(secondRun).extracting(AgentResponse::getType)
                .containsExactly(
                        AgentResponse.Type.TOOL_CALL,
                        AgentResponse.Type.TOOL_RESULT,
                        AgentResponse.Type.FINAL_ANSWER_DELTA,
                        AgentResponse.Type.TOOL_CALL,
                        AgentResponse.Type.FINAL_ANSWER);
    }
}
