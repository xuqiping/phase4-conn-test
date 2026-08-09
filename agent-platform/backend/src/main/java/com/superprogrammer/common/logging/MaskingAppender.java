package com.superprogrammer.common.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.LoggerContextVO;
import ch.qos.logback.classic.spi.ThrowableProxy;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.AppenderBase;
import ch.qos.logback.core.spi.AppenderAttachable;
import ch.qos.logback.core.spi.AppenderAttachableImpl;
import org.slf4j.Marker;
import org.slf4j.event.KeyValuePair;

import java.time.Instant;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * 脱敏追加器（日志系统 LOG-FR-07）：包在真实 appender 外的「一层滤网」，
 * 事件逐条过 {@link LogMasker} 后再下发给内嵌 appender。
 *
 * <p>为什么用包装 appender 而不是 %mask converter / logstash decorator：
 * console（PatternLayout）与文件（LogstashEncoder JSON）是两套渲染管线，
 * converter 只盖 console、JSON 的 message 字段不受影响——双机制必然漂移漏盖。
 * 包装在事件层（getFormattedMessage 上游），一次打码两种格式全生效，SQL 通道同盖（参数可能含 PII）。
 *
 * <p>logback 用法（嵌套 appender-ref，AppenderAttachable 协议同 AsyncAppender）：
 * <pre>&lt;appender name="MASKED_APP" class="...MaskingAppender"&gt;
 *   &lt;appender-ref ref="APP_FILE"/&gt;
 * &lt;/appender&gt;</pre>
 */
public class MaskingAppender extends AppenderBase<ILoggingEvent> implements AppenderAttachable<ILoggingEvent> {

    private final AppenderAttachableImpl<ILoggingEvent> delegates = new AppenderAttachableImpl<>();

    @Override
    protected void append(ILoggingEvent event) {
        String formatted = event.getFormattedMessage();
        String masked = LogMasker.mask(formatted);
        delegates.appendLoopOnAppenders(masked.equals(formatted) ? event : new MaskedEvent(event, masked));
    }

    @Override
    public void addAppender(Appender<ILoggingEvent> newAppender) {
        delegates.addAppender(newAppender);
    }

    @Override
    public Iterator<Appender<ILoggingEvent>> iteratorForAppenders() {
        return delegates.iteratorForAppenders();
    }

    @Override
    public Appender<ILoggingEvent> getAppender(String name) {
        return delegates.getAppender(name);
    }

    @Override
    public boolean isAttached(Appender<ILoggingEvent> appender) {
        return delegates.isAttached(appender);
    }

    @Override
    public void detachAndStopAllAppenders() {
        delegates.detachAndStopAllAppenders();
    }

    @Override
    public boolean detachAppender(Appender<ILoggingEvent> appender) {
        return delegates.detachAppender(appender);
    }

    @Override
    public boolean detachAppender(String name) {
        return delegates.detachAppender(name);
    }

    /** 事件装饰器：全部委托原事件，仅 getFormattedMessage 返回打码后文本。 */
    private record MaskedEvent(ILoggingEvent delegate, String maskedMessage) implements ILoggingEvent {
        @Override
        public String getFormattedMessage() {
            return maskedMessage;
        }

        @Override
        public String getThreadName() {
            return delegate.getThreadName();
        }

        @Override
        public Level getLevel() {
            return delegate.getLevel();
        }

        @Override
        public String getMessage() {
            return delegate.getMessage();
        }

        @Override
        public Object[] getArgumentArray() {
            return delegate.getArgumentArray();
        }

        @Override
        public String getLoggerName() {
            return delegate.getLoggerName();
        }

        @Override
        public LoggerContextVO getLoggerContextVO() {
            return delegate.getLoggerContextVO();
        }

        @Override
        public IThrowableProxy getThrowableProxy() {
            return delegate.getThrowableProxy();
        }

        @Override
        public StackTraceElement[] getCallerData() {
            return delegate.getCallerData();
        }

        @Override
        public boolean hasCallerData() {
            return delegate.hasCallerData();
        }

        @Override
        public List<Marker> getMarkerList() {
            return delegate.getMarkerList();
        }

        @Override
        public Map<String, String> getMDCPropertyMap() {
            return delegate.getMDCPropertyMap();
        }

        @Override
        public Map<String, String> getMdc() {
            return delegate.getMdc();
        }

        @Override
        public long getTimeStamp() {
            return delegate.getTimeStamp();
        }

        @Override
        public int getNanoseconds() {
            return delegate.getNanoseconds();
        }

        @Override
        public Instant getInstant() {
            return delegate.getInstant();
        }

        @Override
        public long getSequenceNumber() {
            return delegate.getSequenceNumber();
        }

        @Override
        public List<KeyValuePair> getKeyValuePairs() {
            return delegate.getKeyValuePairs();
        }

        @Override
        public void prepareForDeferredProcessing() {
            delegate.prepareForDeferredProcessing();
        }
    }
}
