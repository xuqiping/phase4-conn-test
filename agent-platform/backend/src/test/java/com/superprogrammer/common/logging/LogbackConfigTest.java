package com.superprogrammer.common.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.logging.logback.LogbackLoggingSystem;
import org.springframework.boot.logging.LoggingInitializationContext;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * logback-spring.xml 配置冒烟（日志系统 P0 / LOG-FR-01/04/05）。
 * 纯单测：手动驱动 {@link LogbackLoggingSystem} 加载主 resources 下的 logback-spring.xml，
 * 断言四通道 appender 齐备 + SQL 通道默认 OFF、开 DEBUG 生效——防配置回归（XML 写错只在启动时炸）。
 */
class LogbackConfigTest {

    private final LogbackLoggingSystem loggingSystem =
            new LogbackLoggingSystem(getClass().getClassLoader());

    @AfterEach
    void cleanup() {
        // 复原全局日志状态，避免污染同 JVM 其他测试
        loggingSystem.cleanUp();
    }

    private LoggerContext init(MockEnvironment env) {
        loggingSystem.beforeInitialize();
        loggingSystem.initialize(new LoggingInitializationContext(env), null, null);
        return (LoggerContext) LoggerFactory.getILoggerFactory();
    }

    @SuppressWarnings("unchecked")
    private Appender<ILoggingEvent> appender(LoggerContext ctx, String name) {
        Appender<ILoggingEvent> found = ctx.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).getAppender(name);
        if (found == null) {
            // SQL_FILE 挂在 "sql" logger 上
            found = (Appender<ILoggingEvent>) ctx.getLogger("sql").getAppender(name);
        }
        return found;
    }

    @Test
    void fourChannelAppendersExist() {
        LoggerContext ctx = init(new MockEnvironment());
        assertThat(appender(ctx, "CONSOLE")).as("CONSOLE 控制台").isNotNull();
        assertThat(appender(ctx, "APP_FILE")).as("APP_FILE 全量 JSON").isNotNull();
        assertThat(appender(ctx, "ERROR_FILE")).as("ERROR_FILE 错误 JSON").isNotNull();
        assertThat(appender(ctx, "SQL_FILE")).as("SQL_FILE SQL 通道").isNotNull();
    }

    @Test
    void sqlChannelOffByDefault() {
        LoggerContext ctx = init(new MockEnvironment());
        // 默认 OFF：无 LOG_SQL_ENABLED 时 SQL 通道静默（LOG-FR-04 防刷屏）
        assertThat(ctx.getLogger("sql").getLevel()).isEqualTo(Level.OFF);
        // additivity=false：SQL 不进 root/app.log
        assertThat(ctx.getLogger("sql").isAdditive()).isFalse();
    }

    @Test
    void sqlChannelEnabledByProperty() {
        MockEnvironment env = new MockEnvironment().withProperty("logging.level.sql", "DEBUG");
        LoggerContext ctx = init(env);
        assertThat(ctx.getLogger("sql").getLevel()).isEqualTo(Level.DEBUG);
    }
}
