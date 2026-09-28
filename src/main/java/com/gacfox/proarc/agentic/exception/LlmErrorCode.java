package com.gacfox.proarc.agentic.exception;

/**
 * LLM框架错误码
 */
public enum LlmErrorCode {
    TIMEOUT,
    NETWORK_ERROR,

    PROVIDER_AUTH,
    PROVIDER_BAD_REQUEST,
    PROVIDER_NOT_FOUND,
    PROVIDER_RATE_LIMIT,
    PROVIDER_SERVER,
    PROVIDER_ERROR,

    LOCAL_RATE_LIMITED,
    LOCAL_CONCURRENCY_LIMITED,
    RETRY_EXHAUSTED
}
