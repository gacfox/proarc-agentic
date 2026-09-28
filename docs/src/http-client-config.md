# HTTP 连接池配置

Starter 自动装配了一个名为 `llmHttpClient` 的 reactor-netty `HttpClient` Bean，所有基于 `OpenAiLlmClient` 的调用默认共享这个连接池。它的各项参数可以通过 `proarc.agentic.http.*` 配置前缀调整。

由于依赖了 `spring-boot-configuration-processor`，配置时在 IDE 中可以获得自动提示。

## 配置项

application.yml 中的配置示例。

```yaml
proarc:
  agentic:
    http:
      max-connections: 500
      pending-acquire-timeout: 30s
      pending-acquire-max-count: -1
      max-idle-time: 20s
      max-life-time: 5m
      connect-timeout: 15s
      response-timeout: 180s
      read-timeout: 180s
      write-timeout: 180s
```

全部配置项及其默认值如下。

| 配置项 | 默认值 | 说明 |
|---|---|---|
| `max-connections` | 500 | 连接池最大连接数 |
| `pending-acquire-timeout` | 30s | 从池中获取连接的最大等待时间，超时抛异常 |
| `pending-acquire-max-count` | -1 | 排队等待获取连接的最大数量，-1 表示无限制 |
| `max-idle-time` | 20s | 连接最大空闲时间，超时连接被回收 |
| `max-life-time` | 5m | 连接最大存活时间，超时连接被回收 |
| `connect-timeout` | 15s | 建立 TCP 连接的超时时间 |
| `response-timeout` | 180s | 等待响应的超时时间 |
| `read-timeout` | 180s | 读超时，对应 Netty 的 `ReadTimeoutHandler` |
| `write-timeout` | 180s | 写超时，对应 Netty 的 `WriteTimeoutHandler` |

## 调优建议

默认值是面向一般企业级应用给出的。实际项目中，有几个参数值得根据场景调整。

**超时时间与流式调用的关系：** LLM 生成长文本时耗时较长，`response-timeout` 和 `read-timeout` 的默认值 180s 就是为流式场景考虑的。Netty 的读超时按两次数据到达的间隔计算而不是整体耗时，因此流式响应持续有数据到达时不会轻易触发；但如果模型长时间不出字（例如 reasoning 模型长时间思考），间隔超过 180s 就会触发超时并映射为可重试的 `LlmTimeoutException`。接入这类模型时可以适当调大

**连接数与并发限流的配合：** 如果客户端挂了 `LocalConcurrencyLimitInterceptor`，`max-connections` 设置得比并发上限大一些即可，没有放大连接数的必要；反过来，如果没有限流拦截器，`max-connections` 和 `pending-acquire-timeout` 就是保护本机的最后防线，连接耗尽时获取连接会等待直至超时

**空闲与存活时间：** `max-idle-time` 建议小于服务端和中间链路（LB、NAT）的空闲连接回收时间，避免拿到已被对端关闭的连接；`max-life-time` 定期轮换连接，有助于 DNS 变更和负载均衡生效

## 替换默认实现

`llmHttpClient` 的装配带有 `@ConditionalOnMissingBean(name = "llmHttpClient")` 条件，如果我们声明了同名的 Bean，自动装配会退出，配置项也随之失效。需要特殊网络配置（如代理、自定义 SSL 校验）时，可以自行注册同名 Bean 接管。

```java
@Bean("llmHttpClient")
public HttpClient llmHttpClient() {
    return HttpClient.create()
            .proxy(proxy -> proxy.type(ProxyProvider.Proxy.HTTP)
                    .host("127.0.0.1")
                    .port(7890));
}
```

一个需要知晓的默认行为：自动装配的连接池信任所有 SSL 证书（使用了 `InsecureTrustManagerFactory`），这是为了兼容企业内网自签名证书的自部署推理服务。如果对安全性有严格要求，应通过上面的方式自行注册 Bean 并使用正规的证书校验。
