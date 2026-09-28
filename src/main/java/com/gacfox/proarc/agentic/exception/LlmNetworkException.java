package com.gacfox.proarc.agentic.exception;

/**
 * 网络异常（DNS失败、连接重置、SSL握手失败等）
 */
public class LlmNetworkException extends LlmClientException {

    public LlmNetworkException(String message, Throwable cause, String provider, String model) {
        super(message, cause, LlmErrorCode.NETWORK_ERROR, provider, model, true);
    }
}
