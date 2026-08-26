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
        // request 传 null：FORBIDDEN 会触发 publishAuthzDenied(null)，但空 ObjectProvider
        // getIfAvailable()=null → 安全跳过，不阻码映射断言。
        ResponseEntity<R<java.util.Map<String, Object>>> resp =
                handler.handleBusinessException(new BusinessException(code), null);
        return (HttpStatus) resp.getStatusCode();
    }

    // 12x-1 C1：异常载荷透传——withData 非 null 并入 R.data；无载荷维持现状（data=null）
    @Test
    void businessExceptionDataPassedThrough() {
        ResponseEntity<R<java.util.Map<String, Object>>> withData = handler.handleBusinessException(
                new BusinessException(ErrorCode.RATE_LIMIT, "发送过于频繁，请 35 秒后再试")
                        .withData(java.util.Map.of("retryAfterSeconds", 35L)), null);
        assertThat(withData.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(withData.getBody().getData()).containsEntry("retryAfterSeconds", 35L);

        ResponseEntity<R<java.util.Map<String, Object>>> noData = handler.handleBusinessException(
                new BusinessException(ErrorCode.BAD_REQUEST, "普通业务异常"), null);
        assertThat(noData.getBody().getData()).isNull();
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
