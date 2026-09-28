package com.gacfox.proarc.agentic.exception;

/**
 * 认证/权限异常（401/403）
 */
public class LlmAuthException extends LlmProviderException {

    public LlmAuthException(String message, Throwable cause,
                            String provider, String model,
                            int statusCode, String responseBody, String providerErrorCode) {
        super(message, cause, LlmErrorCode.PROVIDER_AUTH, provider, model, false,
                statusCode, responseBody, providerErrorCode, null);
    }
}
