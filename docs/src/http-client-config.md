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
      read-timeout: 180s
      write-timeout: 180s
      allow-insecure: false
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
| `response-timeout` | 不启用 | 整体响应超时，**包含整个流式周期**，流式场景不建议启用；默认 null 表示不设上限 |
| `read-timeout` | 180s | 读空闲超时，对应 Netty 的 `ReadTimeoutHandler`，按两次数据到达的间隔计算 |
| `write-timeout` | 180s | 写超时，对应 Netty 的 `WriteTimeoutHandler` |
| `allow-insecure` | false | 是否跳过 HTTPS 证书校验，true 表示信任所有证书，仅适用于自签名证书的内网环境，生产环境应保持 false |

## 调优建议

默认值是面向一般企业级应用给出的。实际项目中，有几个参数值得根据场景调整。

**超时时间与流式调用的关系：** 框架的所有调用（包括阻塞模式）底层都走 SSE 流式链路，因此流式保护由 `read-timeout` 的空闲语义承担：模型只要持续出字就不会触发超时，长时间思考（如 reasoning 模型）导致的数据间隔超过 180s 才会断开，映射为可重试的 `LlmTimeoutException`，接入这类模型时可以适当调大。`response-timeout` 统计的是整个流的总时长，长文本生成很容易超限并被中途掐断（此时已有内容产出，重试被保护机制禁止，请求必然失败），因此默认不启用；只有确实需要「单次调用最长不超过 X」硬上限的场景才显式配置它

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

## 证书校验

默认情况下，自动装配的连接池对 HTTPS 端点执行正规的证书校验（使用 JVM 默认信任库）。如果 LLM 服务部署在企业内网并使用了自签名证书，可以通过 `allow-insecure: true` 跳过证书校验：

```yaml
proarc:
  agentic:
    http:
      allow-insecure: true
```

开启后所有证书（包括过期、伪造的证书）都会被信任，存在中间人攻击风险，启动时会输出 WARN 日志提示，请勿在生产环境或对公网端点开启。如需使用私有 CA 证书做正规校验，可通过上面的方式自行注册 `llmHttpClient` Bean 并配置自定义 SSL 上下文。
