# 快速开始

本篇笔记带我们在一个 Spring Boot 3.x 工程中使用 ProArc Agentic 快速实现简单的 LLM 对话和带工具调用的 ReAct 智能体。

## 环境要求

- JDK 21+
- Spring Boot 3.x
- 一个 OpenAI 兼容端点（OpenAI 官方、DeepSeek 兼容端点、llama.cpp 本地部署等均可）及其 API Key

## 安装与引入

框架目前需要本地下载并安装，先克隆源码仓库并使用`mvn install`。

```bash
git clone https://github.com/gacfox/proarc-agentic.git
cd proarc-agentic
mvn install
```

安装完成后，在业务工程的 `pom.xml` 中引入依赖。

```xml
<dependency>
    <groupId>com.gacfox</groupId>
    <artifactId>proarc-agentic-spring-boot-starter</artifactId>
    <version>1.0-SNAPSHOT</version>
</dependency>
```

引入依赖后，Starter 会自动装配两个 Bean，名为 `llmHttpClient` 的 reactor-netty 连接池，以及全局工具注册中心 `ToolRegistry`。前者供我们构建客户端时复用，后者会自动扫描并注册所有标注了 `@AgenticTool` 的工具方法。

## 第一次对话

构建客户端需要两样东西：描述模型接入信息的 `ModelInfo`，以及自动装配好的 `llmHttpClient`。

```java
import com.gacfox.proarc.agentic.client.LlmClient;
import com.gacfox.proarc.agentic.client.OpenAiLlmClient;
import com.gacfox.proarc.agentic.model.ChatRequest;
import com.gacfox.proarc.agentic.model.UserMessage;
import com.gacfox.proarc.agentic.model.openai.ModelInfo;
import com.gacfox.proarc.agentic.model.openai.ModelResponse;

import java.util.List;

ModelInfo modelInfo = ModelInfo.builder()
        .provider("openai")
        .model("deepseek-v4-flash")
        .endpoint("http://localhost:11434/v1/chat/completions")
        .sk(System.getenv("LLM_API_KEY"))
        .capabilities(List.of(ModelInfo.CAPABILITY_REASONING, ModelInfo.CAPABILITY_TOOL))
        .build();

LlmClient llmClient = OpenAiLlmClient.builder()
        .modelInfo(modelInfo)
        .httpClient(llmHttpClient) // 注入自动装配的 llmHttpClient
        .build();

ModelResponse response = llmClient.blockingChat(ChatRequest.builder()
        .messages(List.of(new UserMessage("你好，介绍一下你自己")))
        .build());

System.out.println(response.extractBlockingContent());
```

`ModelInfo` 中的 `capabilities` 用于声明模型的额外能力，这里声明了 `reasoning`（支持思考）和 `tool`（支持工具调用）。这个声明会影响框架的行为，例如只有声明了 `reasoning` 的模型，请求中的 `enableThinking` 开关才会被真正传递给端点。

注意：实际开发中，`model`、`endpoint`、`sk`等配置不应硬编码到Java代码中，而是应该抽离到SpringBoot配置文件、配置中心或数据库。

## 第一个智能体

接下来我们给模型装上一个查询天气的工具，然后让 ReAct 智能体来驱动整个「思考—调用—回答」的过程。

定义工具只需要在 Spring Bean 的方法上添加 `@AgenticTool` 注解。

```java
import com.gacfox.proarc.agentic.tool.AgenticTool;
import com.gacfox.proarc.agentic.tool.AgenticToolParam;
import lombok.Data;
import org.springframework.stereotype.Component;

@Component
public class WeatherTools {

    @Data
    public static class WeatherQuery {
        @AgenticToolParam(name = "city", description = "城市名，例如：北京")
        private String city;
    }

    @AgenticTool(name = "query_weather", description = "查询指定城市的实时天气")
    public String queryWeather(WeatherQuery query) {
        // 实际项目中这里调用真实的天气服务
        return "晴，气温 26℃，微风";
    }
}
```

Starter 中的 `BeanPostProcessor` 会自动发现这个方法并注册到 `ToolRegistry`，工具的 JSON Schema 由框架根据参数 DTO 的字段注解自动生成，我们不需要手写。

然后构建智能体执行器并运行。

```java
import com.gacfox.proarc.agentic.agent.AgentContext;
import com.gacfox.proarc.agentic.agent.ReActAgentExecutor;

ReActAgentExecutor agent = ReActAgentExecutor.builder()
        .defaultLlmClient(llmClient)
        .toolRegistry(toolRegistry) // 注入自动装配的 ToolRegistry
        .defaultToolNames(List.of("query_weather"))
        .build();

agent.execute(AgentContext.builder()
                .messages(List.of(new UserMessage("北京今天天气怎么样？")))
                .build())
        .doOnNext(event -> System.out.println("[" + event.getType() + "] " + event.getContent()))
        .blockLast();
```

执行过程中，智能体会以事件流的形式输出每一步的进展：模型的思考（`THINKING`）、发起的工具调用（`TOOL_CALL`）、工具返回的结果（`TOOL_RESULT`），直到最终通过内置的 `final_answer` 工具提交答案（`FINAL_ANSWER`）。典型的输出类似这样。

```
[TOOL_CALL] query_weather {"city":"北京"}
[TOOL_RESULT] 晴，气温 26℃，微风
[FINAL_ANSWER] 北京今天晴，气温 26℃，微风，适合外出。
```

到这里，我们已经走通了框架最核心的两条路径。后续章节会分别深入客户端、消息模型、拦截器、异常体系、工具、智能体等主题的细节。
