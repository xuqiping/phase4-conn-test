// agent-platform/backend/src/main/java/com/superprogrammer/common/exception/BusinessException.java
package com.superprogrammer.common.exception;

import lombok.Getter;

import java.util.Map;

@Getter
public class BusinessException extends RuntimeException {

    private final int code;

    /**
     * 12x-1 C1：可选结构化载荷（如 429 附 retryAfterSeconds）。
     * null = 绝大多数异常的现状不变；GlobalExceptionHandler 非空时并入 R.data。
     * 非 final：仅供构造后 withData 流式附加（异常构造签名不变，调用方零改动）。
     */
    private transient Map<String, Object> data;

    public BusinessException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.code = errorCode.getCode();
        this.data = null;
    }

    public BusinessException(ErrorCode errorCode, String message) {
        super(message);
        this.code = errorCode.getCode();
        this.data = null;
    }

    public BusinessException(int code, String message) {
        super(message);
        this.code = code;
        this.data = null;
    }

    /** 流式附加载荷：{@code throw new BusinessException(RATE_LIMIT, msg).withData(Map.of("k", v))}。 */
    public BusinessException withData(Map<String, Object> data) {
        this.data = data;
        return this;
    }
}
