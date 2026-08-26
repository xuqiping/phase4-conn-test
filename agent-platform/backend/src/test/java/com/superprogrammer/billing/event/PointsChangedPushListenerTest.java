package com.superprogrammer.billing.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.superprogrammer.chat.websocket.EventsWebSocketHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

/**
 * 计划 E3：事件→JSON 下行字段齐 + 序列化失败丢弃计数（不影响后续）。
 * AFTER_COMMIT/回滚不发语义由注解保证（Spring TX 集成层面）。
 */
@ExtendWith(MockitoExtension.class)
class PointsChangedPushListenerTest {

    @Mock
    private EventsWebSocketHandler handler;
    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private PointsChangedPushListener listener;

    @Test
    void onEvent_pushesJsonWithAllFields() {
        PointsChangedEvent e = PointsChangedEvent.builder()
                .userId(5L).scope(PointsChangedEvent.SCOPE_GROUP).groupId(3L)
                .balanceAfter(new BigDecimal("120.00")).delta(new BigDecimal("-8.00"))
                .reason("media-settle:42").build();

        listener.onEvent(e);

        verify(handler).push(eq(5L), argThat(json ->
                json.contains("\"type\":\"points.changed\"")
                        && json.contains("\"scope\":\"GROUP\"")
                        && json.contains("\"groupId\":3")
                        && json.contains("120.00")
                        && json.contains("-8.00")
                        && json.contains("\"ts\":")));
        assertThat(listener.stats()).containsEntry("pushed", 1L).containsEntry("dropped", 0L);
    }

    @Test
    void serializeFailure_droppedAndCounted() throws Exception {
        PointsChangedEvent e = PointsChangedEvent.builder()
                .userId(5L).scope(PointsChangedEvent.SCOPE_PERSONAL)
                .balanceAfter(BigDecimal.ONE).delta(BigDecimal.ONE).reason("r").build();

        org.mockito.Mockito.doThrow(new RuntimeException("boom"))
                .when(objectMapper).writeValueAsString(org.mockito.ArgumentMatchers.any());
        listener.onEvent(e);

        assertThat(listener.stats()).containsEntry("pushed", 0L).containsEntry("dropped", 1L);
    }
}
