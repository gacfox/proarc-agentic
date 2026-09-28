package com.gacfox.proarc.agentic.exception;

import lombok.Getter;

/**
 * 重试耗尽异常
 */
@Getter
public class LlmRetryExhaustedException extends LlmException {
    private final int attempts;
    private final Throwable lastCause;

    public LlmRetryExhaustedException(String message, String provider, String model,
                                      int attempts, Throwable lastCause) {
        super(message, lastCause, LlmErrorCode.RETRY_EXHAUSTED, provider, model, false);
        this.attempts = attempts;
        this.lastCause = lastCause;
    }
}
