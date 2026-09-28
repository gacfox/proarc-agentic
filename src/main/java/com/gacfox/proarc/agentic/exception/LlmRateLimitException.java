package com.gacfox.proarc.agentic.exception;

/**
 * Provider限流异常（429）
 */
public class LlmRateLimitException extends LlmProviderException {

    public LlmRateLimitException(String message, Throwable cause,
                                 String provider, String model,
                                 int statusCode, String responseBody, String providerErrorCode,
                                 Long retryAfterMillis) {
        super(message, cause, LlmErrorCode.PROVIDER_RATE_LIMIT, provider, model, true,
                statusCode, responseBody, providerErrorCode, retryAfterMillis);
    }
}
