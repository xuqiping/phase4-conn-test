package com.superprogrammer.common.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RequestLogFilter 单测（LOG-FR-06 / AC：每请求恰好一行摘要，字段齐全，无 body）。
 */
class RequestLogFilterTest {

    private final RequestLogFilter filter = new RequestLogFilter();
    private ListAppender<ILoggingEvent> listAppender;

    @AfterEach
    void tearDown() {
        if (listAppender != null) {
            ((Logger) LoggerFactory.getLogger(RequestLogFilter.class)).detachAppender(listAppender);
        }
        MDC.clear();
    }

    private ListAppender<ILoggingEvent> attach() {
        Logger logger = (Logger) LoggerFactory.getLogger(RequestLogFilter.class);
        listAppender = new ListAppender<>();
        listAppender.start();
        logger.addAppender(listAppender);
        return listAppender;
    }

    @Test
    void oneSummaryLinePerRequestWithAllFieldsNoBody() throws Exception {
        ListAppender<ILoggingEvent> events = attach();
        MDC.put("userId", "42");
        MDC.put("traceId", "trace-1");
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/chat/send");
        request.setContent("{\"message\":\"secret body\"}".getBytes());

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertThat(events.list).hasSize(1);
        ILoggingEvent line = events.list.get(0);
        assertThat(line.getLevel()).isEqualTo(Level.INFO);
        assertThat(line.getFormattedMessage())
                .startsWith("request POST /api/chat/send status=200 costMs=")
                .doesNotContain("secret body");
    }

    @Test
    void summaryLoggedEvenWhenChainThrows() {
        ListAppender<ILoggingEvent> events = attach();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/x");
        MockFilterChain chain = new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res)
                    throws jakarta.servlet.ServletException {
                throw new jakarta.servlet.ServletException("boom");
            }
        };
        try {
            filter.doFilter(request, new MockHttpServletResponse(), chain);
        } catch (Exception ignored) {
            // 摘要行在 finally：异常也照打
        }
        assertThat(events.list).hasSize(1);
        assertThat(events.list.get(0).getFormattedMessage()).contains("GET /api/x");
    }
}
