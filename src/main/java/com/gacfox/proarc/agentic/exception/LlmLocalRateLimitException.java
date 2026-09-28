package com.gacfox.proarc.agentic.exception;

/**
 * 本机限流异常
 */
public class LlmLocalRateLimitException extends LlmException {
    public LlmLocalRateLimitException(String message, String provider, String model) {
        super(message, null, LlmErrorCode.LOCAL_RATE_LIMITED, provider, model, true);
    }
}
