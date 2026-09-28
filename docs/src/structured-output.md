# 结构化输出

很多场景下我们需要的不是一段自然语言回答，而是可以直接入库或驱动流程的结构化数据，比如从一段文本中提取订单信息、对用户反馈做分类打标。`StructuredExecutor` 就是为此设计的，它把目标 Java 类型转换为工具定义，强制模型通过工具调用返回数据，最后反序列化为类型安全的 Java 对象。

## 基本用法

`StructuredExecutor` 由一个 `LlmClient` 构造，不依赖 Spring，可以在任何位置直接 new 出来。

```java
StructuredExecutor executor = new StructuredExecutor(llmClient);
```

假设我们要从用户反馈中提取结构化信息，先定义结果类型。这里复用了工具的 schema 生成机制，因此字段上同样使用 `@AgenticToolParam` 注解。

```java
@Data
public class FeedbackAnalysis {
    @AgenticToolParam(name = "sentiment", description = "情感倾向：positive/negative/neutral")
    private String sentiment;

    @AgenticToolParam(name = "category", description = "反馈类别")
    private String category;

    @AgenticToolParam(name = "summary", description = "一句话总结")
    private String summary;

    @AgenticToolParam(name = "keywords", description = "关键词列表", required = false)
    private List<String> keywords;
}
```

然后构造请求并执行。

```java
StructuredResponse<FeedbackAnalysis> response = executor.execute(
        StructuredChatRequest.<FeedbackAnalysis>builder()
                .messages(List.of(new UserMessage("物流太慢了，等了一周才收到，不过产品质量确实不错")))
                .responseType(FeedbackAnalysis.class)
                .temperature(0.1)
                .build());

FeedbackAnalysis result = response.extract();
```

`StructuredResponse` 上除了 `extract()` 拿到反序列化后的结果对象外，还可以获取原始信息：`getArguments()` 是模型生成的参数 JSON 字符串，`getRawResponse()` 是完整的模型响应（含 token 用量），`getToolCall()` 是原始的工具调用对象。排查 bad case 时这些原始信息很有用。

## 工作原理

结构化输出的实现没有依赖各 Provider 私有的 JSON Mode 或 Structured Outputs 参数，而是建立在工具调用这个通用能力上。执行过程是这样的：

1. 根据 `responseType` 用 `AgenticSchemaBuilder` 生成一个工具定义，工具名默认为 `structured_output`；
2. 构造请求时强制 `tool_choice` 指向该工具，即模型必须调用它；
3. 如果消息列表中没有系统消息，自动在头部注入一条指令，要求模型必须以结构化形式作答、必须且只能调用一次工具、未知值在类型允许时填 null；
4. 从响应中提取工具调用的参数 JSON，反序列化为 `responseType` 类型的对象。

由于 `tool_choice` 是强制的，模型无法用纯文本搪塞，输出必然符合 schema。这套机制对任何支持工具调用的 OpenAI 兼容端点都有效，不依赖 Provider 的特定扩展能力。

## 请求参数

`StructuredChatRequest` 的采样参数（temperature、topP、topK、presencePenalty、frequencyPenalty、seed、maxTokens、enableThinking）与 `ChatRequest` 语义一致，不再赘述。除此之外有三个特有字段。

| 字段 | 默认值 | 说明 |
|---|---|---|
| `responseType` | 必填 | 结构化输出类型 |
| `toolName` | `structured_output` | 结构化输出工具名，一般无需修改 |
| `instruction` | `null` | 追加到系统指令之后的额外指令 |

`instruction` 用于补充领域性的提取要求，它会被拼接在默认指令之后。例如我们可以要求模型用中文填充总结字段。

```java
StructuredChatRequest.<FeedbackAnalysis>builder()
        .messages(messages)
        .responseType(FeedbackAnalysis.class)
        .instruction("summary 字段请使用中文，keywords 最多提取 5 个")
        .build();
```

## 类型约束与注意事项

结果类型的字段约束与工具参数 DTO 完全一致：每个字段必须标注 `@AgenticToolParam`，不支持 `Map` 类型，集合必须声明泛型元素类型，详细的类型映射规则见「工具定义与注册」一章。

另外还有几点实践经验：

- **把 temperature 调低：** 结构化提取是确定性任务，0 到 0.3 之间通常效果最好
- **系统提示词：** 字段的 `description` 是模型理解字段含义的唯一依据，写得越明确，提取质量越高
- **模型需要支持工具调用：** 即 `ModelInfo.capabilities` 对应的模型本身支持 tool 能力，否则强制 `tool_choice` 会被端点拒绝
- **解析失败会抛异常：** 如果模型生成的 JSON 无法反序列化为目标类型（比如把 integer 字段填成了字符串），会抛出 `IllegalStateException`，其中带有目标类型信息。实际项目中建议对这类异常做记录，积累 bad case 用于优化描述和指令
