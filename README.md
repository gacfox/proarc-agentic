# ProArc Agentic

ProArc Agentic 是一个基于 Spring Boot 的大语言模型（LLM）智能体开发框架，来源于一系列企业级项目的实践沉淀。它以 Spring Boot Starter 的形式提供，引入依赖后即可在工程中快速构建 LLM 对话、工具调用与 ReAct 智能体应用。

框架主要包含以下能力：

- LLM 客户端：面向 OpenAI 兼容端点，支持阻塞式与流式调用、多模态消息、reasoning 模型
- 拦截器机制：环绕式客户端拦截器链，内置重试（Retry-After + 指数退避）、单机 QPS 限流、并发限流，支持动态 Header 扩展
- 异常体系：统一的 LLM 异常模型，区分 Provider 错误与本地错误，并标注是否可重试
- 工具注册：通过 `@AgenticTool` 注解声明工具，自动扫描注册并生成 JSON Schema
- ReAct 智能体：`ReActAgentExecutor` 驱动「思考—行动—观察」循环，以事件流形式输出执行过程，支持流式增量输出与循环拦截器
- 结构化输出：基于强制工具调用实现，直接返回反序列化后的 Java 对象
- 提示词模板：内置 Mustache 模板工具

## 环境要求

- JDK 21+
- Spring Boot 3.x

## 安装

克隆本仓库并在本地安装。

```bash
git clone https://github.com/gacfox/proarc-agentic.git
cd proarc-agentic
mvn install
```

安装完成后，`com.gacfox:proarc-agentic-spring-boot-starter:1.0-SNAPSHOT` 会进入本地 Maven 仓库。

## 引入

在使用方的 `pom.xml` 中添加依赖。

```xml
<dependency>
    <groupId>com.gacfox</groupId>
    <artifactId>proarc-agentic-spring-boot-starter</artifactId>
    <version>1.0-SNAPSHOT</version>
</dependency>
```

## 快速示例

引入依赖后，Starter 会自动装配一个名为 `llmHttpClient` 的连接池 Bean 和全局工具注册中心 `ToolRegistry`。下面我们构建一个客户端并发起一次对话。

```java
ModelInfo modelInfo = ModelInfo.builder()
        .provider("openai")
        .model("qwen-plus")
        .endpoint("https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions")
        .sk(System.getenv("LLM_API_KEY"))
        .capabilities(List.of(ModelInfo.CAPABILITY_REASONING, ModelInfo.CAPABILITY_TOOL))
        .build();

LlmClient llmClient = OpenAiLlmClient.builder()
        .modelInfo(modelInfo)
        .httpClient(llmHttpClient) // 注入自动装配的连接池 Bean
        .build();

ModelResponse response = llmClient.blockingChat(ChatRequest.builder()
        .messages(List.of(new UserMessage("你好，介绍一下你自己")))
        .build());

System.out.println(response.extractBlockingContent());
```

运行一个 ReAct 智能体也同样直接，定义好工具后交给 `ReActAgentExecutor` 即可。

```java
@Component
public class WeatherTools {
    @AgenticTool(name = "query_weather", description = "查询指定城市的天气")
    public String queryWeather(@AgenticToolParam(name = "city", description = "城市名") WeatherQuery query) {
        return "晴，26℃";
    }
}

ReActAgentExecutor agent = ReActAgentExecutor.builder()
        .defaultLlmClient(llmClient)
        .toolRegistry(toolRegistry) // 注入自动装配的工具注册中心
        .defaultToolNames(List.of("query_weather"))
        .build();

agent.execute(AgentContext.builder()
                .messages(List.of(new UserMessage("北京今天天气怎么样？")))
                .build())
        .doOnNext(event -> System.out.println(event.getType() + ": " + event.getContent()))
        .blockLast();
```

## 文档

完整使用文档见 [https://gacfox.github.io/proarc-agentic/](https://gacfox.github.io/proarc-agentic/)。

## License

本项目基于 [MIT License](LICENSE) 开源。
