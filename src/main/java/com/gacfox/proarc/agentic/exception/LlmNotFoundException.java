package com.gacfox.proarc.agentic.exception;

/**
 * Provider资源不存在异常（404）
 */
public class LlmNotFoundException extends LlmProviderException {

    public LlmNotFoundException(String message, Throwable cause,
                                String provider, String model,
                                int statusCode, String responseBody, String providerErrorCode) {
        super(message, cause, LlmErrorCode.PROVIDER_NOT_FOUND, provider, model, false,
                statusCode, responseBody, providerErrorCode, null);
    }
}
