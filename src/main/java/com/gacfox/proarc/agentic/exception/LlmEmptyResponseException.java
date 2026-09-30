package com.gacfox.proarc.agentic.exception;

/**
 * 空响应异常：Provider 返回了正常的 HTTP 200 但响应流中没有任何有效内容
 */
public class LlmEmptyResponseException extends LlmClientException {

    public LlmEmptyResponseException(String message, String provider, String model) {
        super(message, null, LlmErrorCode.EMPTY_RESPONSE, provider, model, true);
    }
}
