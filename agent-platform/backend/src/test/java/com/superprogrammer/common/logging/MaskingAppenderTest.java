package com.superprogrammer.common.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MaskingAppender 事件层打码单测（LOG-FR-07）：脱敏后下发内嵌 appender，
 * 未命中原样透传（同一事件对象，零拷贝）。
 */
class MaskingAppenderTest {

    private MaskingAppender buildWith(ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> list) {
        LoggerContext ctx = new LoggerContext();
        MaskingAppender masking = new MaskingAppender();
        masking.setContext(ctx);
        list.setContext(ctx);
        list.start();
        masking.addAppender(list);
        masking.start();
        return masking;
    }

    private LoggingEvent event(LoggerContext ctx, String message) {
        LoggingEvent e = new LoggingEvent();
        e.setLoggerContextRemoteView(ctx.getLoggerContextRemoteView());
        e.setLoggerName("test");
        e.setLevel(Level.INFO);
        e.setMessage(message);
        return e;
    }

    @Test
    void sensitiveMessageMaskedBeforeDelegate() {
        LoggerContext ctx = new LoggerContext();
        ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> list = new ListAppender<>();
        MaskingAppender masking = buildWith(list);

        masking.doAppend(event(ctx, "登录成功 手机 13812348000"));
        assertThat(list.list).hasSize(1);
        assertThat(list.list.get(0).getFormattedMessage()).isEqualTo("登录成功 手机 138****8000");
        masking.stop();
    }

    @Test
    void cleanMessagePassesThroughSameInstance() {
        LoggerContext ctx = new LoggerContext();
        ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> list = new ListAppender<>();
        MaskingAppender masking = buildWith(list);

        LoggingEvent clean = event(ctx, "普通日志行 cost=42");
        masking.doAppend(clean);
        assertThat(list.list.get(0)).isSameAs(clean);
        masking.stop();
    }
}
