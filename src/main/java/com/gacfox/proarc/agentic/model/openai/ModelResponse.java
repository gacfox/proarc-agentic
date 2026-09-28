package com.gacfox.proarc.agentic.model.openai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 标准（OpenAI规范）大模型响应
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ModelResponse implements Serializable {
    /**
     * 消息ID
     */
    @JsonProperty("id")
    private String id;
    /**
     * 创建时间戳
     */
    @JsonProperty("created")
    private Integer created;
    /**
     * 模型名称
     */
    @JsonProperty("model")
    private String model;
    /**
     * 对象类型（固定值chat.completion）
     */
    @JsonProperty("object")
    private String object;
    /**
     * 回复列表
     */
    @JsonProperty("choices")
    private List<Choice> choices;
    /**
     * tokens使用情况
     */
    @JsonProperty("usage")
    private Usage usage;

    /**
     * 获取第1个choice的回复文本，仅阻塞调用时有效
     *
     * @return 回复文本，无内容时返回null
     */
    public String extractBlockingContent() {
        Message message = getFirstChoiceMessage();
        return message != null && message.getContent() != null ? message.getContent().toString() : null;
    }

    /**
     * 获取第1个choice的Reasoning内容，仅阻塞调用时有效
     *
     * @return Reasoning内容，无Reasoning时返回null
     */
    public String extractBlockingReasoningContent() {
        Message message = getFirstChoiceMessage();
        return message != null ? message.getReasoningContent() : null;
    }

    /**
     * 获取第1个choice的工具调用列表，仅阻塞调用时有效
     *
     * @return 工具调用列表，无工具调用时返回空列表
     */
    public List<ToolCall> extractBlockingToolCalls() {
        Message message = getFirstChoiceMessage();
        return message != null && message.getToolCalls() != null ? message.getToolCalls() : Collections.emptyList();
    }

    /**
     * 获取第1个choice的停止原因，仅阻塞调用时有效
     *
     * @return 停止原因，如 stop、tool_calls、length
     */
    public String extractBlockingFinishReason() {
        if (choices == null || choices.isEmpty()) {
            return null;
        }
        return choices.getFirst().getFinishReason();
    }

    private Message getFirstChoiceMessage() {
        if (choices == null || choices.isEmpty()) {
            return null;
        }
        return choices.getFirst().getMessage();
    }

    /**
     * 将流式响应chunk聚合为完整响应：content/reasoning按序拼接，tool_calls按index聚合，
     * usage与finishReason取最后一个非空值
     *
     * @param chunks 流式响应chunk列表
     * @return 聚合后的完整响应
     */
    public static ModelResponse mergeStreamChunks(List<ModelResponse> chunks) {
        Map<Integer, StringBuilder> contentMap = new HashMap<>();
        Map<Integer, StringBuilder> reasoningMap = new HashMap<>();
        Map<Integer, String> roleMap = new HashMap<>();
        Map<Integer, String> finishReasonMap = new HashMap<>();
        Map<Integer, Map<Integer, ToolCallBuilder>> toolCallsMap = new TreeMap<>();
        String id = null;
        Integer created = null;
        String model = null;
        Usage usage = null;

        for (ModelResponse chunk : chunks) {
            if (chunk == null) {
                continue;
            }
            if (id == null && chunk.getId() != null) {
                id = chunk.getId();
            }
            if (created == null && chunk.getCreated() != null) {
                created = chunk.getCreated();
            }
            if (model == null && chunk.getModel() != null) {
                model = chunk.getModel();
            }
            if (chunk.getUsage() != null) {
                usage = chunk.getUsage();
            }
            if (chunk.getChoices() == null) {
                continue;
            }
            for (Choice choice : chunk.getChoices()) {
                int choiceIdx = choice.getIndex() != null ? choice.getIndex() : 0;
                Delta delta = choice.getDelta();
                if (delta != null) {
                    if (delta.getRole() != null) {
                        roleMap.put(choiceIdx, delta.getRole());
                    }
                    if (delta.getContent() != null) {
                        contentMap.computeIfAbsent(choiceIdx, k -> new StringBuilder()).append(delta.getContent());
                    }
                    if (delta.getReasoning() != null) {
                        reasoningMap.computeIfAbsent(choiceIdx, k -> new StringBuilder()).append(delta.getReasoning());
                    }
                    if (delta.getToolCalls() != null) {
                        Map<Integer, ToolCallBuilder> tcMap = toolCallsMap.computeIfAbsent(choiceIdx, k -> new TreeMap<>());
                        for (ToolCall tc : delta.getToolCalls()) {
                            int tcIdx = tc.getIndex() != null ? tc.getIndex() : tcMap.size();
                            ToolCallBuilder tcb = tcMap.computeIfAbsent(tcIdx, k -> new ToolCallBuilder());
                            if (tc.getId() != null) {
                                tcb.id = tc.getId();
                            }
                            if (tc.getType() != null) {
                                tcb.type = tc.getType();
                            }
                            if (tc.getFunction() != null) {
                                if (tc.getFunction().getName() != null) {
                                    tcb.functionName = tc.getFunction().getName();
                                }
                                if (tc.getFunction().getArguments() != null) {
                                    tcb.argumentsBuilder.append(tc.getFunction().getArguments());
                                }
                            }
                        }
                    }
                }
                if (choice.getFinishReason() != null) {
                    finishReasonMap.put(choiceIdx, choice.getFinishReason());
                }
            }
        }

        Set<Integer> allChoiceIndices = new LinkedHashSet<>();
        allChoiceIndices.addAll(contentMap.keySet());
        allChoiceIndices.addAll(reasoningMap.keySet());
        allChoiceIndices.addAll(toolCallsMap.keySet());

        List<Choice> mergedChoices = new ArrayList<>();
        for (Integer choiceIdx : allChoiceIndices) {
            List<ToolCall> mergedToolCalls = null;
            Map<Integer, ToolCallBuilder> tcMap = toolCallsMap.get(choiceIdx);
            if (tcMap != null && !tcMap.isEmpty()) {
                mergedToolCalls = new ArrayList<>();
                for (Map.Entry<Integer, ToolCallBuilder> entry : tcMap.entrySet()) {
                    ToolCallBuilder tcb = entry.getValue();
                    mergedToolCalls.add(ToolCall.builder()
                            .id(tcb.id)
                            .index(entry.getKey())
                            .type(tcb.type)
                            .function(ToolCallFunction.builder()
                                    .name(tcb.functionName)
                                    .arguments(tcb.argumentsBuilder.toString())
                                    .build())
                            .build());
                }
            }
            Message message = Message.builder()
                    .role(roleMap.getOrDefault(choiceIdx, Message.ROLE_ASSISTANT))
                    .content(contentMap.containsKey(choiceIdx) ? contentMap.get(choiceIdx).toString() : null)
                    .reasoningContent(reasoningMap.containsKey(choiceIdx) ? reasoningMap.get(choiceIdx).toString() : null)
                    .toolCalls(mergedToolCalls)
                    .build();
            mergedChoices.add(Choice.builder()
                    .index(choiceIdx)
                    .message(message)
                    .finishReason(finishReasonMap.get(choiceIdx))
                    .build());
        }

        return ModelResponse.builder()
                .id(id)
                .created(created)
                .model(model)
                .object("chat.completion")
                .choices(mergedChoices)
                .usage(usage)
                .build();
    }

    private static class ToolCallBuilder {
        String id;
        String type;
        String functionName;
        final StringBuilder argumentsBuilder = new StringBuilder();
    }
}
