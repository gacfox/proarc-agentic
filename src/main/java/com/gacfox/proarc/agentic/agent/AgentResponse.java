package com.gacfox.proarc.agentic.agent;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 智能体响应事件
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class AgentResponse implements Serializable {
    /**
     * 智能体响应类型
     */
    public enum Type {
        /**
         * 思考内容（reasoning）
         */
        THINKING,
        /**
         * 工具调用
         */
        TOOL_CALL,
        /**
         * 工具调用结果
         */
        TOOL_RESULT,
        /**
         * 最终回答
         */
        FINAL_ANSWER,
        /**
         * 智能体执行出错
         */
        ERROR,
        /**
         * 思考内容增量片段（仅streaming模式产生）
         */
        THINKING_DELTA,
        /**
         * 最终回答增量片段（仅streaming模式产生）
         */
        FINAL_ANSWER_DELTA
    }

    /**
     * 智能体响应类型
     */
    private Type type;
    /**
     * 智能体响应文本
     */
    private String content;
    /**
     * 智能体调用的工具名
     */
    private String toolName;
    /**
     * 智能体调用的工具调用ID
     */
    private String toolCallId;
    /**
     * 智能体调用的工具参数
     */
    private String toolArguments;

    /**
     * 生成智能体思考事件
     *
     * @param content 思考内容
     * @return 事件对象
     */
    public static AgentResponse thinking(String content) {
        return AgentResponse.builder().type(Type.THINKING).content(content).build();
    }

    /**
     * 生成智能体工具调用事件
     *
     * @param toolCallId    工具调用ID
     * @param toolName      工具名
     * @param toolArguments 工具参数JSON字符串
     * @return 事件对象
     */
    public static AgentResponse toolCall(String toolCallId, String toolName, String toolArguments) {
        return AgentResponse.builder().type(Type.TOOL_CALL)
                .toolCallId(toolCallId).toolName(toolName).toolArguments(toolArguments).build();
    }

    /**
     * 生成智能体工具调用结果事件
     *
     * @param toolCallId 工具调用ID
     * @param toolName   工具名
     * @param content    工具调用结果字符串
     * @return 事件对象
     */
    public static AgentResponse toolResult(String toolCallId, String toolName, String content) {
        return AgentResponse.builder().type(Type.TOOL_RESULT)
                .toolCallId(toolCallId).toolName(toolName).content(content).build();
    }

    /**
     * 生成智能体最终回答结果事件
     *
     * @param content 回答内容
     * @return 事件对象
     */
    public static AgentResponse finalAnswer(String content) {
        return AgentResponse.builder().type(Type.FINAL_ANSWER).content(content).build();
    }

    /**
     * 生成智能体出错事件
     *
     * @param content 错误信息字符串
     * @return 事件对象
     */
    public static AgentResponse error(String content) {
        return AgentResponse.builder().type(Type.ERROR).content(content).build();
    }

    /**
     * 生成智能体思考增量事件（仅streaming模式）
     *
     * @param content 思考内容增量片段
     * @return 事件对象
     */
    public static AgentResponse thinkingDelta(String content) {
        return AgentResponse.builder().type(Type.THINKING_DELTA).content(content).build();
    }

    /**
     * 生成智能体最终回答增量事件（仅streaming模式）
     *
     * @param content 回答内容增量片段
     * @return 事件对象
     */
    public static AgentResponse finalAnswerDelta(String content) {
        return AgentResponse.builder().type(Type.FINAL_ANSWER_DELTA).content(content).build();
    }
}
