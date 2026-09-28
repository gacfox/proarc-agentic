package com.gacfox.proarc.agentic.exception;

/**
 * Provider服务端异常（5xx）
 */
public class LlmServerException extends LlmProviderException {

    public LlmServerException(String message, Throwable cause,
                              String provider, String model,
                              int statusCode, String responseBody, String providerErrorCode) {
        super(message, cause, LlmErrorCode.PROVIDER_SERVER, provider, model, true,
                statusCode, responseBody, providerErrorCode, null);
    }
}
