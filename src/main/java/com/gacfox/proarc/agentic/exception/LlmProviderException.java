package com.gacfox.proarc.agentic.exception;

import lombok.Getter;

/**
 * Provider返回错误响应的异常基类
 */
@Getter
public class LlmProviderException extends LlmException {

    private final int statusCode;
    private final String responseBody;
    private final String providerErrorCode;
    private final Long retryAfterMillis;

    public LlmProviderException(String message, Throwable cause,
                                LlmErrorCode errorCode, String provider, String model, boolean retryable,
                                int statusCode, String responseBody, String providerErrorCode,
                                Long retryAfterMillis) {
        super(message, cause, errorCode, provider, model, retryable);
        this.statusCode = statusCode;
        this.responseBody = responseBody;
        this.providerErrorCode = providerErrorCode;
        this.retryAfterMillis = retryAfterMillis;
    }
}
