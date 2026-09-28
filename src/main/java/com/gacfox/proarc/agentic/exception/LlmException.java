package com.gacfox.proarc.agentic.exception;

import lombok.Getter;

/**
 * LLM框架异常基类
 */
@Getter
public abstract class LlmException extends RuntimeException {

    private final LlmErrorCode errorCode;
    private final String provider;
    private final String model;
    private final boolean retryable;

    protected LlmException(String message, Throwable cause,
                           LlmErrorCode errorCode, String provider, String model, boolean retryable) {
        super(message, cause);
        this.errorCode = errorCode;
        this.provider = provider;
        this.model = model;
        this.retryable = retryable;
    }
}
