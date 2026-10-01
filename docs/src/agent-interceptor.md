# 智能体拦截器

客户端层的 `LlmInterceptor` 拦截的是单次 LLM 请求，而智能体层的 `AgentInterceptor` 拦截的是一整轮 ReAct 循环。它在每一轮循环开始前后都能切入，适合做循环级别的审计、守卫和人工介入。

## 拦截器接口

```java
public interface AgentInterceptor {
    AgentLoopResult intercept(AgentContext context, AgentInterceptorChain chain);
    default int getOrder() {
        return 0;
    }
}
```

与客户端拦截器一样，它是环绕式的：通过 `chain.next(context)` 触发本轮的下游逻辑（后续拦截器，直到实际的 LLM 调用与工具执行），我们可以在其前后编写本轮的前置和后置逻辑。`getOrder()` 决定多个拦截器的执行顺序，值越小越靠前。

每一次 ReAct 循环都会完整执行一遍拦截器链，这一点与客户端拦截器（每次 LLM 请求执行一遍）在智能体场景下粒度不同：一轮循环对应一次 LLM 调用加若干次工具执行。

构建执行器时传入拦截器列表。

```java
ReActAgentExecutor agent = ReActAgentExecutor.builder()
        .defaultLlmClient(llmClient)
        .toolRegistry(toolRegistry)
        .interceptors(List.of(new AuditInterceptor(), new BudgetGuardInterceptor()))
        .build();
```

## 循环结果 AgentLoopResult

拦截器方法返回的 `AgentLoopResult` 描述本轮循环的结局，通过三个静态工厂方法构造。

| 工厂方法 | 语义 |
|---|---|
| `continueWith(responses)` | 本轮正常结束，进入下一轮循环 |
| `finishWith(responses)` | 整次执行结束（通常是模型提交了最终答案） |
| `suspendWith(responses)` | 挂起执行，事件流就此关闭，不再进入下一轮 |

`chain.next(context)` 返回的就是下游产生的 `AgentLoopResult`，拦截器可以原样返回，也可以替换它——这是实现各类守卫的关键。

## 示例：循环审计

一个记录每轮耗时的审计拦截器。

```java
public class AuditInterceptor implements AgentInterceptor {
    private static final Logger log = LoggerFactory.getLogger(AuditInterceptor.class);

    @Override
    public AgentLoopResult intercept(AgentContext context, AgentInterceptorChain chain) {
        long start = System.currentTimeMillis();
        AgentLoopResult result = chain.next(context);
        log.info("Agent loop finished, contextId={}, elapsed={}ms, events={}",
                context.getContextId(),
                System.currentTimeMillis() - start,
                result.getResponses().size());
        return result;
    }
}
```

## 示例：轮数与预算守卫

在调用下游之前检查条件，不满足时直接以 `finishWith` 终止执行。

```java
public class BudgetGuardInterceptor implements AgentInterceptor {
    @Override
    public AgentLoopResult intercept(AgentContext context, AgentInterceptorChain chain) {
        if (overBudget(context)) {
            return AgentLoopResult.finishWith(List.of(
                    AgentResponse.error("Token budget exhausted")));
        }
        return chain.next(context);
    }
}
```

由于拦截器在 `chain.next` 之前返回，本轮不会真正发起 LLM 请求，成本就被控制住了。

## 挂起执行

`suspendWith` 用于在循环边界主动中止本次执行：拦截器返回挂起结果后，执行器停止循环并关闭事件流，但 `AgentContext` 中的消息历史保持完整且协议合法，之后可以用同一个上下文再次调用 `execute` 继续执行。

```java
public class QuotaInterceptor implements AgentInterceptor {
    private final QuotaService quotaService;

    @Override
    public AgentLoopResult intercept(AgentContext context, AgentInterceptorChain chain) {
        if (quotaService.exhausted(context.getContextId())) {
            quotaService.notifyRefill(context.getContextId()); // 通知配额管理员充值
            return AgentLoopResult.suspendWith(List.of(
                    AgentResponse.suspended("Token quota exhausted, waiting for refill.")));
        }
        return chain.next(context);
    }
}
```

这个例子的思路是：每轮循环开始前检查配额，耗尽时挂起执行并通知管理员。由于拦截发生在 `chain.next` 之前，本轮不会真正发起 LLM 请求。配额充值后，业务方用保存的 `AgentContext` 再次调用 `execute`，智能体带着完整的消息历史从下一轮循环继续工作。

如果挂起的原因是「某次工具调用需要人工审批」，循环粒度就不合适了——循环拦截器看到 tool_calls 时工具往往已经执行。这种场景应该使用[工具调用拦截器](tool-call-interceptor.md)：它在单次工具调用粒度环绕拦截，配合 `AgentSuspendException` 可以在执行前挂起，并在审批通过后从未执行的那次调用精确恢复。

## 三层拦截器的分工

最后用一张表总结三层拦截器的分工，实际使用中我们可以据此决定逻辑挂在哪里。

| 维度 | `LlmInterceptor` | `AgentInterceptor` | `ToolCallInterceptor` |
|---|---|---|---|
| 拦截粒度 | 单次 LLM 请求 | 一整轮 ReAct 循环 | 单次工具调用 |
| 触发频率 | 每次请求一次 | 每轮循环一次 | 每次工具执行一次 |
| 能感知 | 请求参数、模型配置、响应 | 上下文、消息历史、事件列表、循环结局 | 工具名、参数、上下文 |
| 典型用途 | 重试、限流、审计请求、动态 Header | 循环审计、预算守卫、轮次间人工介入 | 工具审计、参数改写、调用审批、挂起恢复 |
