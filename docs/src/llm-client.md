# LlmClient

`LlmClient` 是框架中大语言模型客户端的核心接口，定义了三种操作：获取模型配置、阻塞式调用和流式调用。

```java
public interface LlmClient {
    ModelInfo getModelInfo();
    ModelResponse blockingChat(ChatRequest chatRequest);
    Flux<ModelResponse> streamingChat(ChatRequest chatRequest);
}
```

框架目前仅内置了一个泛用性最强的实现 `OpenAiLlmClient`，面向所有兼容 OpenAI Chat Completions 规范的端点。由于这类端点已经成为事实标准，大部分第三方Provider也都提供兼容服务可以直接接入。

## 模型配置 ModelInfo

构建客户端之前，我们需要先通过 `ModelInfo` 描述模型的接入信息。

| 字段 | 说明                                                        |
|---|-----------------------------------------------------------|
| `provider` | 提供商标识，当前固定使用 `openai` 表示 OpenAI 兼容 API 端点                 |
| `model` | 模型名，如 `gpt-4o`、`deepseek-v4-flash`                        |
| `endpoint` | 完整的 Chat Completions 端点地址，注意要包含 `/v1/chat/completions` 部分 |
| `sk` | API 密钥，会以 `Authorization: Bearer <sk>` 的形式附加到请求头          |
| `contextLength` | 模型上下文长度，仅供参考，框架不做强制校验                                     |
| `maxTokens` | 模型最大输出 tokens 数，仅供参考                                      |
| `capabilities` | 模型能力列表，可选值见下文                                             |
| `headers` | 自定义静态请求头，会覆盖同名默认请求头                                       |

`capabilities` 声明模型的额外能力，目前支持三个枚举值：

- `ModelInfo.CAPABILITY_REASONING`（`"reasoning"`）：模型支持思考。只有声明了该能力，`ChatRequest` 中的 `enableThinking` 开关才会转换为请求中的 `chat_template_kwargs` 传递给端点。
- `ModelInfo.CAPABILITY_TOOL`（`"tool"`）：模型支持工具调用。
- `ModelInfo.CAPABILITY_VISION`（`"vision"`）：模型支持图片输入。

需要说明的是，`capabilities` 目前主要用于框架侧的行为控制（例如上述 `enableThinking` 的处理逻辑），不会因为缺少声明而拦截请求本身，实际调用是否成功仍取决于端点。

## 构建客户端

`OpenAiLlmClient` 通过 Builder 构建，除了 `modelInfo` 之外，还可以传入连接池、拦截器列表和动态 Header 提供者。

```java
LlmClient llmClient = OpenAiLlmClient.builder()
        .modelInfo(modelInfo)
        .httpClient(llmHttpClient)          // 注入自动装配的连接池 Bean
        .interceptors(List.of(              // 可选，客户端拦截器
                new LocalConcurrencyLimitInterceptor(20),
                new RetryInterceptor()))
        .headerProvider((info, request) ->  // 可选，动态 Header
                Map.of("X-Trace-Id", MDC.get("traceId")))
        .build();
```

`httpClient` 参数我们一般直接注入 Starter 自动装配的 `llmHttpClient`，它是基于 reactor-netty 的连接池，配置项可以通过 `proarc.agentic.http.*` 调整（详见「HTTP 连接池配置」一章）。如果业务中需要隔离不同模型服务的连接资源，也可以自行构建独立的 `HttpClient` 传入。拦截器和动态 Header 的完整介绍见「拦截器与动态 Header」一章。

## 阻塞式调用

阻塞式调用返回聚合后的完整响应 `ModelResponse`。

```java
ModelResponse response = llmClient.blockingChat(ChatRequest.builder()
        .messages(List.of(new UserMessage("写一首关于春天的短诗")))
        .temperature(0.8)
        .build());

String content = response.extractBlockingContent();
```

一个值得了解的实现细节是，`OpenAiLlmClient` 的阻塞式调用内部实际上是发起流式请求，然后将所有分片通过 `ModelResponse.mergeStreamChunks` 聚合为完整响应。这样做保证了两种调用方式走完全一致的链路和拦截器，同时避免了长文本生成时阻塞 HTTP 请求容易超时的问题。

`ModelResponse` 上提供了几个便捷的提取方法，均针对第一个 choice：

- `extractBlockingContent()`：回复文本，无内容时返回 `null`
- `extractBlockingReasoningContent()`：reasoning 模型的思考内容，无时返回 `null`
- `extractBlockingToolCalls()`：工具调用列表，无时返回空列表
- `extractBlockingFinishReason()`：停止原因，如 `stop`、`tool_calls`、`length`

如果需要 tokens 用量统计，可以从 `response.getUsage()` 中获取 `promptTokens`、`completionTokens` 和 `totalTokens`。

## 流式调用

流式调用返回 `Flux<ModelResponse>`，每个元素是一个响应分片，分片中的增量内容在 `choice.delta` 里。

```java
llmClient.streamingChat(ChatRequest.builder()
                .messages(List.of(new UserMessage("写一首关于春天的短诗")))
                .build())
        .subscribe(chunk -> {
            Delta delta = chunk.getChoices().getFirst().getDelta();
            if (StringUtils.hasText(delta.getReasoning())) {
                System.out.print(delta.getReasoning()); // 思考内容增量
            }
            if (StringUtils.hasText(delta.getContent())) {
                System.out.print(delta.getContent());   // 正文内容增量
            }
        });
```

这里有两点需要注意。第一，`Delta.reasoning` 字段通过 `@JsonAlias` 同时兼容了 `reasoning`、`thinking`、`reasoning_content` 三种字段名，不同服务商对思考内容的命名差异由框架抹平。第二，如果需要将流式分片聚合为完整响应（例如入库保存），可以直接使用 `ModelResponse.mergeStreamChunks(chunks)` 静态方法，它会按序拼接 content 和 reasoning、按 index 聚合 tool_calls，usage 和 finishReason 取最后一个非空值。

## 多模态消息

对于声明了 `vision` 能力的模型，我们可以发送图文混合的多模态消息。

```java
UserMessage message = new UserMessage(List.of(
        MultiModalContent.builder()
                .type(MultiModalContent.TYPE_TEXT)
                .text("这张图片里有什么？")
                .build(),
        MultiModalContent.builder()
                .type(MultiModalContent.TYPE_IMAGE)
                .imageUrl(new ImageUrl("https://example.com/photo.jpg"))
                .build()
));
```

## 扩展新的客户端实现

如果未来需要支持非 OpenAI 兼容的协议，可以继承 `AbstractLlmClient`。它已经处理好了拦截器链的组装和异常映射，子类只需要实现 `doBlockingChat` 和 `doStreamingChat` 两个方法负责实际的 HTTP 调用，并在异常路径上调用 `mapException` 将底层异常转换为框架异常体系。
