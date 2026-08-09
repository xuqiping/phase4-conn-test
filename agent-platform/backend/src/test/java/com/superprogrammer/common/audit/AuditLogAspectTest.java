package com.superprogrammer.common.audit;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.MDC;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AuditLogAspect 单测（LOG-FR-10）：成功落 SUCCESS / 异常落 FAIL 后原样上抛 / 参数脱敏截断。
 */
class AuditLogAspectTest {

    @Target(ElementType.METHOD)
    @Retention(RetentionPolicy.RUNTIME)
    @interface Dummy {
    }

    private final AuditLogService service = mock(AuditLogService.class);
    private final AuditLogAspect aspect = new AuditLogAspect(service);

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    private AuditLog annotation() throws NoSuchMethodException {
        return Sample.class.getMethod("doIt", Long.class, String.class).getAnnotation(AuditLog.class);
    }

    @SuppressWarnings("unused")
    static class Sample {
        @AuditLog(module = "role", action = "update_permissions", targetType = "role")
        public void doIt(Long id, String note) {
        }
    }

    private ProceedingJoinPoint pjp(Object... args) throws Throwable {
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        MethodSignature sig = mock(MethodSignature.class);
        when(sig.getParameterNames()).thenReturn(new String[]{"id", "note"});
        when(pjp.getSignature()).thenReturn(sig);
        when(pjp.getArgs()).thenReturn(args);
        return pjp;
    }

    @Test
    void successRecordsSuccessWithTargetIdAndMaskedDetail() throws Throwable {
        when(service.fromMdc(any(), any(), any(), any(), any(), any()))
                .thenAnswer(inv -> {
                    AuditLogEntity e = new AuditLogEntity();
                    e.setModule(inv.getArgument(0));
                    e.setAction(inv.getArgument(1));
                    e.setTargetId(inv.getArgument(3));
                    e.setDetailJson(inv.getArgument(4));
                    e.setResult(inv.getArgument(5));
                    return e;
                });
        ProceedingJoinPoint pjp = pjp(3L, "手机 13812348000");
        when(pjp.proceed()).thenReturn("ok");

        Object result = aspect.around(pjp, annotation());

        assertThat(result).isEqualTo("ok");
        ArgumentCaptor<AuditLogEntity> captor = ArgumentCaptor.forClass(AuditLogEntity.class);
        verify(service).record(captor.capture());
        AuditLogEntity row = captor.getValue();
        assertThat(row.getModule()).isEqualTo("role");
        assertThat(row.getTargetId()).isEqualTo("3");
        assertThat(row.getResult()).isEqualTo(AuditLogEntity.RESULT_SUCCESS);
        // detail 脱敏生效：手机号打码
        assertThat(row.getDetailJson()).contains("138****8000").doesNotContain("13812348000");
    }

    @Test
    void failureRecordsFailAndRethrows() throws Throwable {
        when(service.fromMdc(any(), any(), any(), any(), any(), any()))
                .thenAnswer(inv -> {
                    AuditLogEntity e = new AuditLogEntity();
                    e.setResult(inv.getArgument(5));
                    e.setDetailJson(inv.getArgument(4));
                    return e;
                });
        ProceedingJoinPoint pjp = pjp(7L, "x");
        when(pjp.proceed()).thenThrow(new IllegalStateException("role locked"));

        assertThatThrownBy(() -> aspect.around(pjp, annotation()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("role locked");
        ArgumentCaptor<AuditLogEntity> captor = ArgumentCaptor.forClass(AuditLogEntity.class);
        verify(service).record(captor.capture());
        assertThat(captor.getValue().getResult()).isEqualTo(AuditLogEntity.RESULT_FAIL);
        assertThat(captor.getValue().getDetailJson()).contains("role locked");
    }

    @Test
    void neverBlocksWhenServiceRejects() throws Throwable {
        // record 自身不抛（队列满已在 service 内吞），此处验证 proceed 一定执行
        ProceedingJoinPoint pjp = pjp(1L, "n");
        when(pjp.proceed()).thenReturn("done");
        when(service.fromMdc(any(), any(), any(), any(), any(), any())).thenReturn(new AuditLogEntity());
        Object result = aspect.around(pjp, annotation());
        assertThat(result).isEqualTo("done");
        verify(pjp).proceed();
    }
}
