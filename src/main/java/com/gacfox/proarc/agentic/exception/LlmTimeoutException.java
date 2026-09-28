package com.gacfox.proarc.agentic.exception;

/**
 * 超时异常（connect/response/read/write timeout）
 */
public class LlmTimeoutException extends LlmClientException {

    public LlmTimeoutException(String message, Throwable cause, String provider, String model) {
        super(message, cause, LlmErrorCode.TIMEOUT, provider, model, true);
    }
}
