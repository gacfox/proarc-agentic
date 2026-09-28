# 提示词模板

在智能体应用中，提示词往往需要根据运行时数据动态拼装。框架内置了基于 Mustache 的模板工具类 `PromptTemplate`，它足够轻量，只有一个静态方法。

```java
String prompt = PromptTemplate.build(templateString, params);
```

第一个参数是 Mustache 模板字符串，第二个参数是模板变量 Map，返回渲染后的提示词文本。

## 使用示例

```java
String template = """
        你是一名{{role}}。
        请用{{language}}回答用户的问题，回答不超过{{maxLength}}字。

        用户问题：{{question}}
        """;

String prompt = PromptTemplate.build(template, Map.of(
        "role", "资深 Java 架构师",
        "language", "中文",
        "maxLength", 200,
        "question", "如何做接口幂等设计？"
));
```

渲染结果。

```
你是一名资深 Java 架构师。
请用中文回答用户的问题，回答不超过200字。

用户问题：如何做接口幂等设计？
```

渲染出的提示词直接放进消息即可使用。

```java
new SystemMessage(prompt)
```

## Mustache 语法支持

`PromptTemplate` 底层使用的是 mustache.java 的默认工厂，标准 Mustache 语法都可以使用。除了最基本的 `{{variable}}` 插值，实际写提示词时比较有用的还有几种。

列表渲染，适合动态注入知识条目。

```
已知的相关资料：
{{#docs}}
- {{.}}
{{/docs}}
```

条件区块，适合按场景裁剪提示词片段。

```
{{#strictMode}}
注意：你的回答必须严格基于给定资料，不允许使用外部知识。
{{/strictMode}}
```

这里传入 `Map.of("strictMode", true)` 时该段落才会出现在最终提示词中。

关于转义有一点需要注意：Mustache 默认会对 `{{variable}}` 插值做 HTML 转义，如果变量内容中可能包含 `&`、`<`、`>` 等字符且不希望被转义，可以使用 `{{{variable}}}` 或 `{{&variable}}` 的形式。提示词场景一般不涉及 HTML，建议统一使用三括号形式，避免内容被意外转义后影响模型理解。

## 实践建议

- **模板放在资源文件中管理：** 示例中为了演示方便使用了内嵌字符串，实际项目里建议把提示词模板放到 `resources/prompts/` 目录下，代码中读取文件内容再渲染，这样提示词的调整不需要改动代码
- **变量名即契约：** 模板变量与 Map 的 key 严格对应，未提供的变量渲染为空字符串，拼写错误不会报错只会静默丢失内容，模板和传参最好由同一段代码附近的常量管理
- **不要用它拼 JSON：** 如果目标是构造结构化数据，应该使用 Jackson 序列化，Mustache 只适合纯文本的提示词场景
