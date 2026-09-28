# 拦截器与动态 Header

实际项目中，LLM 调用往往需要附加重试、限流、审计、链路追踪这类横切逻辑。框架在客户端层提供了环绕式拦截器机制，让这些逻辑与主调用链路解耦。本篇笔记介绍 `LlmInterceptor` 拦截器链、三个内置拦截器，以及动态 Header 提供者 `LlmHeaderProvider`。

## 拦截器接口

`LlmInterceptor` 定义了阻塞和流式两个拦截方法，都有默认实现（直接放行），因此我们只需要覆盖关心的那个。

```java
public interface LlmInterceptor {
    default ModelResponse interceptBlocking(ModelRequest request, ModelInfo modelInfo, LlmInterceptorChain chain) {
        return chain.nextBlocking(request);
    }
    default Flux<ModelResponse> interceptStreaming(ModelRequest request, ModelInfo modelInfo, LlmInterceptorChain chain) {
        return chain.nextStreaming(request);
    }
    default int getOrder() {
        return 0;
    }
}
```

拦截器是环绕式的：我们通过 `chain.nextXxx()` 掌握下游调用，在它之前编写前置逻辑，之后编写后置逻辑，异常则通过 try-catch（阻塞式）或 Reactor 操作符（流式）自行处理。`getOrder()` 决定拦截器在链中的顺序，值越小越靠前，越先执行前置逻辑。

构建客户端时通过 `interceptors` 传入拦截器列表，框架会自动按 order 排序。

```java
LlmClient llmClient = OpenAiLlmClient.builder()
        .modelInfo(modelInfo)
        .httpClient(llmHttpClient)
        .interceptors(List.of(
                new LocalConcurrencyLimitInterceptor(20),
                new LocalRateLimiterInterceptor(50),
                new RetryInterceptor()))
        .build();
```

## RetryInterceptor：重试

`RetryInterceptor` 提供自动重试能力，默认配置是重试 20 次，延迟范围 1s 到 30s。

```java
new RetryInterceptor();                       // 默认：20 次，1s ~ 30s
new RetryInterceptor(5, 500, 10_000);         // 自定义：5 次，500ms ~ 10s
```

重试策略有几个设计细节值得我们了解。

- **只重试可重试的异常：** 框架异常体系中的每个异常都标注了 `retryable` 标记，超时、网络错误、429 限流、5xx 服务端错误可重试；认证失败（401/403）、参数错误（400）、资源不存在（404）等不可重试，会直接抛出。详细的错误映射见「异常体系」一章。
- **429 时优先遵守 Retry-After：** 如果 Provider 在 429 响应中携带了 `Retry-After` 响应头，重试延迟直接采用该值，否则使用指数退避加随机抖动。
- **流式调用的重试有保护：** 流式请求一旦已经开始产出分片，中途失败不再重试，避免重复输出内容；只有尚未产出任何分片时的失败才会触发重试。

重试全部耗尽后抛出 `LlmRetryExhaustedException`，其中携带了尝试次数和最后一次的失败原因。

内置的 `RetryInterceptor` order 为 100，位于链中相对靠后的位置。

## LocalRateLimiterInterceptor：单机 QPS 限流

`LocalRateLimiterInterceptor` 基于令牌桶算法实现单机 QPS 限流，令牌耗尽时直接抛出 `LlmLocalRateLimitException`，而不是排队等待。

```java
new LocalRateLimiterInterceptor(50); // 最大 50 QPS
```

它的 order 为 -100，在链中非常靠前，超限的请求会在最早阶段被拒绝，不占用后续拦截器和连接资源。由于是快速失败的语义，这个异常同样标注为可重试，可以配合外层调用方或 `RetryInterceptor` 做错峰重试。

## LocalConcurrencyLimitInterceptor：单机并发限流

`LocalConcurrencyLimitInterceptor` 基于信号量限制同时在途的请求数，同样采用快速失败语义，信号量耗尽时抛出 `LlmConcurrencyLimitException`。

```java
new LocalConcurrencyLimitInterceptor(20); // 最大 20 并发
```

它的 order 为 -110，比 QPS 限流还要靠前。流式调用中，信号量会在流结束时（`doFinally`）释放，因此并发数统计的是整个请求的生命周期，而不只是发起请求的瞬间。

两个限流拦截器都是单机语义，不依赖 Redis 等外部组件，适合保护本机资源；如果集群层面需要全局限流，可以参照它们的实现自定义拦截器对接分布式限流组件。

## 动态 Header：LlmHeaderProvider

有些场景下请求头需要在每次调用时动态计算，比如 token 轮换、AK/SK 签名、链路追踪 ID、租户路由等。`LlmHeaderProvider` 就是为此设计的函数式接口。

```java
public interface LlmHeaderProvider {
    Map<String, String> resolve(ModelInfo modelInfo, ModelRequest request);
}
```

它在每次发起 LLM 请求时被调用，返回的键值对会附加到请求头中。Header 的优先级从高到低是：`LlmHeaderProvider` 动态返回的 Header → `ModelInfo.headers` 中的静态 Header → 框架默认 Header（如 `Authorization: Bearer <sk>`）。高优先级覆盖同名低优先级。

一个基于 MDC 透传链路追踪 ID 的例子。

```java
LlmClient llmClient = OpenAiLlmClient.builder()
        .modelInfo(modelInfo)
        .httpClient(llmHttpClient)
        .headerProvider((info, request) -> Map.of("X-Trace-Id", MDC.get("traceId")))
        .build();
```

需要注意，`resolve` 在每次请求时都会执行，其中不要做耗时操作；如果需要缓存（例如签名结果的有效期），由实现方自行处理。
