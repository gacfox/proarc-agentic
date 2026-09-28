package com.gacfox.proarc.agentic.model.openai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * 大模型消息
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class Message implements Serializable {
    /**
     * 角色信息
     */
    @JsonProperty("role")
    private String role;
    /**
     * prompt及业务文本信息
     */
    @JsonProperty("content")
    private Object content;
    /**
     * 推理内容（仅Reasoning模型）
     */
    @JsonProperty("reasoning_content")
    private String reasoningContent;

    /**
     * 工具调用列表（仅工具调用消息）
     */
    @JsonProperty("tool_calls")
    private List<ToolCall> toolCalls;

    /**
     * 工具调用ID（仅工具结果消息）
     */
    @JsonProperty("tool_call_id")
    private String toolCallId;

    /**
     * 用户消息
     */
    public static final String ROLE_USER = "user";
    /**
     * 系统消息
     */
    public static final String ROLE_SYSTEM = "system";
    /**
     * 机器人消息
     */
    public static final String ROLE_ASSISTANT = "assistant";
    /**
     * 工具调用消息
     */
    public static final String ROLE_TOOL = "tool";
}
