package com.superprogrammer.common.exception;

import com.superprogrammer.common.result.R;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 安全体系 S1 顺手修：5 位业务码「前三位 = HTTP 语义」映射测试。
 * 既有 bug F4：40201 曾落 500；RATE_LIMIT 429 曾落 400。
 */
class GlobalExceptionHandlerStatusTest {

    // P3-C9：本类只测业务码映射，使用 Spring 提供的空 ObjectProvider。
    private final GlobalExceptionHandler handler = new GlobalExceptionHandler(
            new org.springframework.beans.factory.support.DefaultListableBeanFactory()
                    .getBeanProvider(org.springframework.context.ApplicationEventPublisher.class));

    private HttpStatus statusOf(ErrorCode code) {
        ResponseEntity<R<Void>> resp = handler.handleBusinessException(new BusinessException(code));
        return (HttpStatus) resp.getStatusCode();
    }

    @Test
    void fiveDigitCodesMapByPrefix() {
        assertThat(statusOf(ErrorCode.FILE_TYPE_NOT_ALLOWED)).isEqualTo(HttpStatus.BAD_REQUEST); // 40010→400
        assertThat(statusOf(ErrorCode.LOGIN_LOCKED)).isEqualTo(HttpStatus.UNAUTHORIZED);          // 40103→401
        assertThat(statusOf(ErrorCode.INSUFFICIENT_POINTS)).isEqualTo(HttpStatus.PAYMENT_REQUIRED); // 40201→402（F4 修复）
        assertThat(statusOf(ErrorCode.ROLE_FORBIDDEN)).isEqualTo(HttpStatus.FORBIDDEN);           // 40301→403
        assertThat(statusOf(ErrorCode.AGENT_NOT_PUBLISHED)).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY); // 42201→422
    }

    @Test
    void threeDigitCodesKeepSemantics() {
        assertThat(statusOf(ErrorCode.BAD_REQUEST)).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(statusOf(ErrorCode.FORBIDDEN)).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(statusOf(ErrorCode.NOT_FOUND)).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(statusOf(ErrorCode.CONFLICT)).isEqualTo(HttpStatus.CONFLICT);
        assertThat(statusOf(ErrorCode.RATE_LIMIT)).isEqualTo(HttpStatus.TOO_MANY_REQUESTS); // 429 不再落 400
        assertThat(statusOf(ErrorCode.INTERNAL_ERROR)).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }
}
