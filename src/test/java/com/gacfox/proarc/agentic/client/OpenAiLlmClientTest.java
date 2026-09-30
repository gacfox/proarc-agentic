package com.gacfox.proarc.agentic.client;

import com.gacfox.proarc.agentic.exception.LlmAuthException;
import com.gacfox.proarc.agentic.exception.LlmBadRequestException;
import com.gacfox.proarc.agentic.exception.LlmRateLimitException;
import com.gacfox.proarc.agentic.exception.LlmServerException;
import com.gacfox.proarc.agentic.model.ChatRequest;
import com.gacfox.proarc.agentic.model.UserMessage;
import com.gacfox.proarc.agentic.model.openai.ModelInfo;
import com.gacfox.proarc.agentic.model.openai.ModelResponse;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.netty.http.client.HttpClient;
import reactor.test.StepVerifier;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenAiLlmClientTest {

    private MockWebServer server;
    private OpenAiLlmClient client;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        ModelInfo modelInfo = ModelInfo.builder()
                .provider("openai")
                .model("test-model")
                .endpoint(server.url("/v1/chat/completions").toString())
                .sk("test-key")
                .build();
        client = OpenAiLlmClient.builder()
                .modelInfo(modelInfo)
                .httpClient(HttpClient.create())
                .build();
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    private static ChatRequest chatRequest() {
        return ChatRequest.builder()
                .messages(List.of(new UserMessage("你好")))
                .build();
    }

    private static String sseChunk(String content) {
        return "{\"id\":\"chatcmpl-1\",\"created\":1,\"model\":\"test-model\","
                + "\"choices\":[{\"index\":0,\"delta\":{\"content\":\"" + content + "\"}}]}";
    }

    private static String sseBody(String... chunks) {
        StringBuilder sb = new StringBuilder();
        for (String chunk : chunks) {
            sb.append("data: ").append(chunk).append("\n\n");
        }
        sb.append("data: [DONE]\n\n");
        return sb.toString();
    }

    private static MockResponse sseResponse(String body) {
        return new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody(body);
    }

    private static MockResponse errorResponse(int code, String message) {
        return new MockResponse()
                .setResponseCode(code)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"error\":{\"message\":\"" + message + "\",\"code\":\"E" + code + "\"}}");
    }

    @Test
    void blockingChatAggregatesSseStream() {
        server.enqueue(sseResponse(sseBody(sseChunk("Hello"), sseChunk(" world"))));

        ModelResponse response = client.blockingChat(chatRequest());

        assertThat(response.extractBlockingContent()).isEqualTo("Hello world");
        assertThat(response.extractBlockingFinishReason()).isNull();
    }

    @Test
    void streamingChatEmitsChunks() {
        server.enqueue(sseResponse(sseBody(sseChunk("a"), sseChunk("b"))));

        StepVerifier.create(client.streamingChat(chatRequest()))
                .expectNextCount(2)
                .verifyComplete();
    }

    @Test
    void sendsAuthorizationHeaderAndRequestBody() throws Exception {
        server.enqueue(sseResponse(sseBody(sseChunk("hi"))));

        client.blockingChat(chatRequest());

        RecordedRequest request = server.takeRequest();
        assertThat(request.getHeader("Authorization")).isEqualTo("Bearer test-key");
        assertThat(request.getPath()).isEqualTo("/v1/chat/completions");
        String body = request.getBody().readUtf8();
        assertThat(body).contains("\"model\":\"test-model\"");
        assertThat(body).contains("\"stream\":true");
        assertThat(body).contains("你好");
    }

    @Test
    void unauthorizedMapsToAuthException() {
        server.enqueue(errorResponse(401, "invalid api key"));

        assertThatThrownBy(() -> client.blockingChat(chatRequest()))
                .isInstanceOfSatisfying(LlmAuthException.class, e -> {
                    assertThat(e.isRetryable()).isFalse();
                    assertThat(e.getStatusCode()).isEqualTo(401);
                    assertThat(e.getMessage()).contains("invalid api key");
                });
    }

    @Test
    void rateLimitMapsWithRetryAfter() {
        server.enqueue(errorResponse(429, "slow down").setHeader("Retry-After", "5"));

        assertThatThrownBy(() -> client.blockingChat(chatRequest()))
                .isInstanceOfSatisfying(LlmRateLimitException.class, e -> {
                    assertThat(e.isRetryable()).isTrue();
                    assertThat(e.getRetryAfterMillis()).isEqualTo(5000L);
                });
    }

    @Test
    void serverErrorMapsToRetryableServerException() {
        server.enqueue(errorResponse(500, "internal error"));

        assertThatThrownBy(() -> client.blockingChat(chatRequest()))
                .isInstanceOfSatisfying(LlmServerException.class, e -> {
                    assertThat(e.isRetryable()).isTrue();
                    assertThat(e.getStatusCode()).isEqualTo(500);
                });
    }

    @Test
    void badRequestMapsToNonRetryableException() {
        server.enqueue(errorResponse(400, "bad request"));

        assertThatThrownBy(() -> client.blockingChat(chatRequest()))
                .isInstanceOfSatisfying(LlmBadRequestException.class, e -> assertThat(e.isRetryable()).isFalse());
    }

    @Test
    void streamingErrorMapsToLlmException() {
        server.enqueue(errorResponse(500, "internal error"));

        StepVerifier.create(client.streamingChat(chatRequest()))
                .expectError(LlmServerException.class)
                .verify();
    }
}
