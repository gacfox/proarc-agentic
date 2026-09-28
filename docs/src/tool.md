# 工具定义与注册

工具调用（Tool Calling）是智能体与外部世界交互的基础。框架中定义一个工具只需要两个注解：在 Spring Bean 的方法上添加 `@AgenticTool`，在其参数 DTO 的字段上添加 `@AgenticToolParam`。框架会自动完成注册和 JSON Schema 生成，我们不需要手写任何 schema 定义。

## 定义工具

下面是一个完整的工具定义示例。

```java
import com.gacfox.proarc.agentic.tool.AgenticTool;
import com.gacfox.proarc.agentic.tool.AgenticToolParam;
import lombok.Data;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class OrderTools {

    @Data
    public static class OrderQuery {
        @AgenticToolParam(name = "order_no", description = "订单号")
        private String orderNo;

        @AgenticToolParam(name = "include_items", description = "是否包含订单明细", required = false)
        private Boolean includeItems;
    }

    @AgenticTool(name = "query_order", description = "根据订单号查询订单信息")
    public String queryOrder(OrderQuery query) {
        // 实际项目中调用订单服务
        return "{\"orderNo\":\"" + query.getOrderNo() + "\",\"status\":\"已发货\"}";
    }
}
```

`@AgenticTool` 有两个必填属性：`name` 是工具名，全局唯一，模型通过它来发起调用；`description` 是工具描述，会直接展示给模型，写清楚工具的用途和适用场景能显著提高模型的选择准确率。

`@AgenticToolParam` 标注在参数 DTO 的字段上：`name` 是暴露给模型的参数名（建议使用蛇形命名，与 JSON 习惯一致），`description` 是参数描述，`required` 声明是否必填，默认为 `true`。

## 方法签名规则

工具方法的签名遵循以下约定：

- 最多声明一个参数 DTO，且该参数必须标注 `@AgenticToolParam`
- 可以额外声明一个 `AgentContext` 类型的参数（位置任意），框架会注入当前的智能体上下文快照，工具可以通过它读写共享变量，详见「ReAct 智能体」一章
- 返回值的 `toString()` 结果作为工具执行结果返回给模型，一般返回 String 或可 JSON 序列化的对象
- 无参数的工具方法可以不声明 DTO 参数，框架会生成空的参数 schema

下面是几个合法的方法签名。

```java
@AgenticTool(name = "a", description = "...")
public String a(MyDto dto) { ... }

@AgenticTool(name = "b", description = "...")
public String b(MyDto dto, AgentContext ctx) { ... }

@AgenticTool(name = "c", description = "...")
public String c() { ... }
```

## 自动注册机制

Starter 自动装配了 `ToolRegistry` 和一个 `BeanPostProcessor`：每个 Spring Bean 初始化完成后，处理器会扫描其方法上是否有 `@AgenticTool` 注解，有则将整个 Bean 注册到 `ToolRegistry`。也就是说，只要工具类是 Spring Bean（`@Component`、`@Service` 等），注册就是全自动的。

注册过程中，框架会做两件事：一是校验方法签名的合法性，签名不满足上述规则时会在应用启动阶段直接抛出异常，问题尽早暴露；二是通过反射生成工具的 JSON Schema。

注册中心基于 `ConcurrentHashMap` 实现，注册与注销都是线程安全的。工具名全局唯一，重复注册同名工具会抛出 `IllegalStateException`。如需手动管理工具，`ToolRegistry` 提供了 `register(ToolDefinition)`、`unregister(String)`、`getAllTools()`、`getAgenticTool(String)` 等方法，具体用法见下一节。

## 动态注册工具

注解方式适合编译期就能确定的工具，而有些场景下工具只有到运行时才能确定：比如从 MCP 等服务同步工具列表、按租户动态开通工具、由配置驱动启停工具。这时我们可以通过编程方式手工构造 `ToolDefinition` 注册到 `ToolRegistry`。

`ToolDefinition` 由四部分组成：工具名、工具描述、JSON Schema 字符串和 `ToolInvoker` 执行体。其中 `ToolInvoker` 是函数式接口，直接写 lambda 即可，入参是模型生成的参数 JSON 字符串和当前上下文快照，返回值的字符串就是工具执行结果。

```java
ToolDefinition toolDef = new ToolDefinition(
        "query_stock",
        "查询股票实时价格",
        jsonSchema,
        (arguments, ctx) -> {
            JsonNode args = objectMapper.readTree(arguments);
            return stockService.getPrice(args.get("code").asText());
        });
toolRegistry.register(toolDef);
```

JSON Schema 有两种来源。一种是直接手写 JSON 字符串，适合对接外部服务时对方已经提供了 schema 的情况。

```java
String jsonSchema = """
        {"type":"function","function":{"name":"query_stock","description":"查询股票实时价格",
        "parameters":{"type":"object","properties":{"code":{"type":"string","description":"股票代码"}},
        "required":["code"]}}}
        """;
```

另一种是复用框架的 `AgenticSchemaBuilder`，用一个 DTO 类生成 schema 后序列化，这样与注解方式共用同一套类型映射规则，也避免了手写 JSON。

```java
Tool tool = new AgenticSchemaBuilder()
        .buildTool("query_stock", "查询股票实时价格", StockQuery.class);
String jsonSchema = objectMapper.writeValueAsString(tool);
```

动态注册的工具与注解注册的工具地位完全相同：注册后在 `AgentContext.toolNames` 或执行器的 `defaultToolNames` 中按名引用，智能体就能正常调用它。需要下线时调用 `unregister(toolName)` 即可，整个注册与注销过程在运行期随时可以进行，不需要重启应用。

## Schema 生成规则

JSON Schema 由 `AgenticSchemaBuilder` 根据参数 DTO 的字段类型反射生成。Java 类型到 JSON Schema 类型的映射关系如下。

| Java 类型 | JSON Schema 类型 |
|---|---|
| `String`、`Character`、枚举、`Date`、`UUID`、`java.time` 时间类型 | `string` |
| `Integer`/`int`、`Long`/`long`、`Short`/`short`、`Byte`/`byte`、`BigInteger` | `integer` |
| `Double`/`double`、`Float`/`float`、`BigDecimal` | `number` |
| `Boolean`/`boolean` | `boolean` |
| 数组、`Collection`（必须声明泛型元素类型） | `array` |
| 自定义 DTO 类 | `object`（递归嵌套生成） |

有几个限制需要注意：

- `Map` 类型不支持，因为 Map 的键值结构无法静态描述
- 集合必须声明具体的泛型元素类型，不支持通配符（`? extends` 上界形式除外）
- DTO 中的每个非 static、非 transient 字段都必须标注 `@AgenticToolParam`，否则启动时报错，这个约束是有意为之，保证暴露给模型的每个参数都有明确的名称和描述
- `static`、`transient` 和合成字段会被自动跳过

## 工具的执行

模型发起工具调用时，`ReActAgentExecutor` 会从注册中心找到对应的 `ToolDefinition`，将模型生成的 JSON 参数反序列化为 DTO 对象，再通过反射调用工具方法。工具抛出的任何异常都会被捕获并转换为 `"Error: <异常信息>"` 形式的工具结果返回给模型，而不是中断智能体循环，模型看到这个结果后通常会修正参数重新调用，这正是我们期望的自愈行为。
