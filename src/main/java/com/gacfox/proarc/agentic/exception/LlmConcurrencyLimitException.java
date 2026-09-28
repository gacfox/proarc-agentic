package com.gacfox.proarc.agentic.exception;

/**
 * 本机并发数超限异常
 */
public class LlmConcurrencyLimitException extends LlmException {
    public LlmConcurrencyLimitException(String message, String provider, String model) {
        super(message, null, LlmErrorCode.LOCAL_CONCURRENCY_LIMITED, provider, model, true);
    }
}
