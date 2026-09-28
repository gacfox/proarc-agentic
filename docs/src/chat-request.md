# 消息与请求参数

本篇笔记介绍框架中的消息模型和对话请求参数。这些类都遵循 OpenAI Chat Completions 规范，字段与 API 请求体一一对应。

## 消息模型

所有消息的基类是 `com.gacfox.proarc.agentic.model.openai.Message`，包含 role、content、reasoningContent、toolCalls、toolCallId 等字段。框架在 `com.gacfox.proarc.agentic.model` 包下提供了四个面向使用方的子类，我们在组装消息列表时一般会用到它们。

| 类 | 角色 | 说明 |
|---|---|---|
| `SystemMessage` | system | 系统提示词，通常在消息列表最前面 |
| `UserMessage` | user | 用户消息，支持纯文本和多模态两种构造方式 |
| `AssistantMessage` | assistant | 助手消息，用于回填历史对话或携带工具调用 |
| `ToolMessage` | tool | 工具调用结果消息，需要关联 toolCallId |

一个多轮对话的消息列表可以这样组装。

```java
List<Message> messages = List.of(
        new SystemMessage("你是一个乐于助人的助手"),
        new UserMessage("Java 中接口和抽象类的区别是什么？"),
        new AssistantMessage("接口和抽象类的主要区别在于……"),
        new UserMessage("那在实际开发中应该怎么选择？")
);
```

需要注意的是，在智能体场景中我们一般不需要手动构造 `AssistantMessage` 和 `ToolMessage`，`ReActAgentExecutor` 会把每轮模型返回的助手消息和工具执行结果自动追加到上下文的消息列表中。

`UserMessage` 还有一个接收 `List<MultiModalContent>` 的构造函数用于图文混合输入，具体用法见「LlmClient」一章的多模态消息部分。

## 请求参数 ChatRequest

`ChatRequest` 是我们面向客户端提交对话的请求对象，字段与 OpenAI 规范的请求参数对应。

| 字段 | 类型 | 默认值 | 说明 |
|---|---|---|---|
| `messages` | `List<Message>` | - | 消息列表，必填 |
| `temperature` | `Double` | `0.7` | 温度 |
| `enableThinking` | `Boolean` | `null` | 是否开启思考，详见下文 |
| `topP` | `Double` | `null` | 核采样参数 |
| `topK` | `Integer` | `null` | 前 k 个最可能 token，仅部分 Provider 支持 |
| `presencePenalty` | `Double` | `0.0` | 惩罚新出现的 token，抑制生成重复内容 |
| `frequencyPenalty` | `Double` | `0.0` | 不惩罚重复出现的 token，抑制重复生成相同词汇，与 presencePenalty 共同作用 |
| `seed` | `Integer` | `null` | 随机数种子 |
| `maxTokens` | `Integer` | `null` | 控制生成文本长度，包含输入输出总 tokens |
| `tools` | `List<Tool>` | `null` | 工具描述列表 |
| `toolChoice` | `Object` | `null` | 工具选择策略，可传 `"auto"`、`"none"` 或指定具体工具 |

使用示例。

```java
ChatRequest request = ChatRequest.builder()
        .messages(messages)
        .temperature(0.3)
        .maxTokens(4096)
        .build();
```

值为 `null` 的字段不会序列化进请求体，因此端点会使用自己的默认值。

## 关于 enableThinking

`enableThinking` 用于开关 reasoning 模型的思考过程，但它生效有两个前提：一是模型本身支持 reasoning，二是 `ModelInfo.capabilities` 中声明了 `reasoning` 能力。满足条件时，框架会将其转换为请求体中的 `chat_template_kwargs.enable_thinking` 字段，这是 vLLM 等推理框架的通行约定。

```java
ChatRequest request = ChatRequest.builder()
        .messages(messages)
        .enableThinking(true)
        .build();
```

另外要注意的是，不同模型输出思考内容的方式并不统一，有的通过独立的 reasoning 字段返回，有的直接在正文中嵌入 `<think>` 标签。框架只负责解析前一种形式（`Delta.reasoning` 兼容了 `reasoning`、`thinking`、`reasoning_content` 三种字段名），使用后一种形式的模型需要我们自行从正文文本中提取。

## 请求级工具描述

`tools` 字段允许我们在单次请求中直接传入工具描述，工具对象 `Tool` 对应 OpenAI 规范的 function 定义。在智能体场景下我们不需要关心这个字段，`ReActAgentExecutor` 会从 `ToolRegistry` 自动构建工具列表并固定使用 `"auto"` 的选择策略；而 `StructuredExecutor` 会强制指定调用结构化输出工具。手动传入 `tools` 主要适用于不使用智能体编排、只想做一次工具调用的场景。
