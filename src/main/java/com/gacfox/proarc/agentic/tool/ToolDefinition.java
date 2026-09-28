package com.gacfox.proarc.agentic.tool;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.io.Serializable;

/**
 * 智能体工具定义
 */
@Getter
@AllArgsConstructor
public class ToolDefinition implements Serializable {
    /**
     * 工具名
     */
    private final String toolName;
    /**
     * 工具描述
     */
    private final String description;
    /**
     * 工具的OpenAI JSON Schema
     */
    private final String jsonSchema;
    /**
     * 工具执行入口
     */
    private final transient ToolInvoker invoker;
}
