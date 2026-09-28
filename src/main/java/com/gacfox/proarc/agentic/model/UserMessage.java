package com.gacfox.proarc.agentic.model;

import com.gacfox.proarc.agentic.model.openai.Message;
import com.gacfox.proarc.agentic.model.openai.MultiModalContent;

import java.util.List;

/**
 * 用户消息
 */
public class UserMessage extends Message {

    /**
     * 创建用户消息
     *
     * @param content 消息内容
     */
    public UserMessage(String content) {
        setRole(ROLE_USER);
        setContent(content);
    }

    /**
     * 多模态消息（图文混合）
     *
     * @param contents 多模态内容列表
     */
    public UserMessage(List<MultiModalContent> contents) {
        setRole(ROLE_USER);
        setContent(contents);
    }
}
