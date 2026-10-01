# 工具调用拦截器

`AgentInterceptor` 拦截的是一整轮 ReAct 循环，而 `ToolCallInterceptor` 拦截的是单次工具调用：模型返回 tool_calls 之后、工具真正执行之前，我们有了介入点。它是实现工具调用审计、参数改写、结果替换、调用拒绝以及 human-in-the-loop 审批的基础设施。

## 拦截器接口

```java
public interface ToolCallInterceptor {
    String intercept(ToolInvocation invocation, ToolCallChain chain) throws Exception;
    default int getOrder() {
        return 0;
    }
}
```

环绕式语义与框架中另外两层拦截器完全一致：`chain.proceed(invocation)` 触发下游（后续拦截器，直到真正的工具执行），返回值就是工具结果字符串。`ToolInvocation` 携带本次调用的完整信息。

| 字段 | 说明 |
|---|---|
| `toolCallId` | 工具调用 ID |
| `toolName` | 工具名 |
| `arguments` | 参数 JSON 字符串，可改写后再放行 |
| `agentContext` | 智能体上下文（活引用），可读写 `variables` |

拦截器的几种典型行为。

| 行为 | 做法 | 效果 |
|---|---|---|
| 放行 | `return chain.proceed(invocation)` | 工具正常执行 |
| 改写参数 | 先 `invocation.setArguments(...)` 再 proceed | 工具收到改写后的参数 |
| 替换结果 | 不 proceed，直接返回字符串 | 工具不执行，该字符串作为结果返回给模型 |
| 拒绝调用 | 不 proceed，返回 `"Error: ..."` | 工具不执行，模型看到拒绝原因后可调整策略 |
| 挂起执行 | 抛出 `AgentSuspendException` | 见下文「挂起与恢复」 |
| 其他异常 | 抛出任意异常 | 异常信息作为 `"Error: ..."` 结果返回给模型 |

构建执行器时传入拦截器列表，`getOrder()` 值越小越靠前。

```java
ReActAgentExecutor agent = ReActAgentExecutor.builder()
        .defaultLlmClient(llmClient)
        .toolRegistry(toolRegistry)
        .toolCallInterceptors(List.of(new AuditInterceptor(), new ApprovalInterceptor()))
        .build();
```

有几点语义值得注意。

- 内置的 `final_answer` 工具同样经过拦截器链。拦截器可以审查甚至改写最终答案（链尾返回的字符串即最终答案），不需要处理时按 `toolName` 过滤即可。
- 只有「真正会执行」的调用才过链：被跳过的（final_answer 之后的剩余调用）、因停止请求被取消的、参数不是合法 JSON 的调用不会触发拦截器。
- 空参数会先规范化为 `{}` 再过链。

## 示例：同步审批

最简单的 human-in-the-loop：拦截器里阻塞等待审批结果（例如由另一个线程完成一个 `CompletableFuture`），批准则放行，拒绝则返回错误文本。

```java
ToolCallInterceptor approval = (invocation, chain) -> {
    boolean approved = approvalService.awaitApproval(
            invocation.getToolName(), invocation.getArguments());
    if (!approved) {
        return "Error: tool call rejected by reviewer.";
    }
    return chain.proceed(invocation);
};
```

## 挂起与恢复

同步审批要求审批在等待期间完成。如果审批是异步的（本次请求挂起，审批人在另一个请求里决定），可以使用挂起信号：在拦截器中抛出 `AgentSuspendException`。

```java
ToolCallInterceptor approval = (invocation, chain) -> {
    if ("final_answer".equals(invocation.getToolName())) {
        return chain.proceed(invocation);
    }
    Object decision = invocation.getAgentContext().getVariables()
            .get("approval:" + invocation.getToolCallId());
    if (decision == null) {
        throw new AgentSuspendException("awaiting approval: " + invocation.getToolCallId());
    }
    return Boolean.TRUE.equals(decision)
            ? chain.proceed(invocation)
            : "Error: tool call rejected by reviewer.";
};
```

挂起时执行器的行为。

1. 当前批次已执行的工具结果正常写入消息历史；触发挂起的调用及之后的调用保持待执行状态，不写结果；
2. 事件流发出一个 `SUSPENDED` 事件（内容为异常 message）并正常完成，订阅方据此区分「跑完了 / 被停了 / 挂起等待」；
3. `AgentContext` 中的消息历史始终是协议合法的：带 tool_calls 的 assistant 消息后面只缺待执行调用的结果。

恢复执行不需要任何新 API：把审批决定写入 `variables`（上面的示例按 `approval:<toolCallId>` 做 key），用同一个上下文再次调用 `execute` 即可。执行器在每轮调用 LLM 之前会先检查消息历史末尾：如果带 tool_calls 的 assistant 消息之后只有 tool 消息，且其中存在未配对结果的调用，会先把这些待执行调用依次执行完（照常经过拦截器链）再发起 LLM 请求。已经执行过的工具不会重复执行。

```java
// 首次执行：收到 SUSPENDED 事件后持久化 context
agent.execute(context).doOnNext(this::render).blockLast();

// 审批人批准后：写入决定并恢复
context.getVariables().put("approval:" + toolCallId, true);
agent.execute(context).doOnNext(this::render).blockLast();
```

`AgentSuspendException` 也可以从工具方法内部抛出，效果相同（例如工具执行到一半发现需要人工确认）。结合「待执行调用恢复」机制，这个信号同样覆盖了进程崩溃后从持久化上下文恢复执行的场景。

## 三层拦截器的分工

| 维度 | `LlmInterceptor` | `AgentInterceptor` | `ToolCallInterceptor` |
|---|---|---|---|
| 拦截粒度 | 单次 LLM 请求 | 一整轮 ReAct 循环 | 单次工具调用 |
| 触发频率 | 每次请求一次 | 每轮循环一次 | 每次工具执行一次 |
| 能感知 | 请求参数、模型配置、响应 | 上下文、消息历史、事件列表、循环结局 | 工具名、参数、上下文 |
| 典型用途 | 重试、限流、审计请求、动态 Header | 循环审计、预算守卫、轮次间人工介入 | 工具审计、参数改写、调用审批、挂起恢复 |
