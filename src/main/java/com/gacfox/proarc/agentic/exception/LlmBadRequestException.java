package com.gacfox.proarc.agentic.exception;

/**
 * Provider请求参数异常（400）
 */
public class LlmBadRequestException extends LlmProviderException {

    public LlmBadRequestException(String message, Throwable cause,
                                  String provider, String model,
                                  int statusCode, String responseBody, String providerErrorCode) {
        super(message, cause, LlmErrorCode.PROVIDER_BAD_REQUEST, provider, model, false,
                statusCode, responseBody, providerErrorCode, null);
    }
}
