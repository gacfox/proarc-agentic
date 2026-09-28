package com.gacfox.proarc.agentic.exception;

/**
 * 本地/网络层异常基类（未收到Provider正常响应时产生的异常）
 */
public abstract class LlmClientException extends LlmException {

    protected LlmClientException(String message, Throwable cause,
                                 LlmErrorCode errorCode, String provider, String model, boolean retryable) {
        super(message, cause, errorCode, provider, model, retryable);
    }
}
