package com.superprogrammer.chat.service.internal;

import com.superprogrammer.chat.dto.MemoryRosterVO;
import com.superprogrammer.chat.service.internal.MemoryDepartedResolver.DepartedInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 计划12 · I3-1 · MemoryDepartedResolver 单测（Mockito，mock rosterService）。
 * <p>
 * 覆盖：null 项目空 / 无 DEPARTED 空 / DEPARTED 集合 + 标注（name 优先/username 回退/date 缺省「未知」）/
 * intersectDeparted 交集（开关关时剔这部分）。
 */
@ExtendWith(MockitoExtension.class)
class MemoryDepartedResolverTest {

    @Mock MemoryRosterService rosterService;

    private MemoryDepartedResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new MemoryDepartedResolver(rosterService);
    }

    private MemoryRosterVO vo(Long uid, String username, String name, String status, OffsetDateTime departedAt) {
        return MemoryRosterVO.builder()
                .userId(uid).username(username).name(name).status(status).departedAt(departedAt).build();
    }

    @Test
    void resolveDeparted_nullProject_empty() {
        assertTrue(resolver.resolveDeparted(null).isEmpty());
        verifyNoInteractions(rosterService);
    }

    @Test
    void resolveDeparted_noDeparted_empty() {
        when(rosterService.getRoster(100L)).thenReturn(List.of(
                vo(1L, "u1", "张三", "ACTIVE", null),
                vo(2L, "u2", null, "ACTIVE", null)));
        assertTrue(resolver.resolveDeparted(100L).isEmpty());
    }

    @Test
    void resolveDeparted_collectsDepartedIdsAndAnnotations() {
        OffsetDateTime at = OffsetDateTime.parse("2026-06-10T12:00:00+08:00");
        when(rosterService.getRoster(100L)).thenReturn(List.of(
                vo(1L, "u1", "张三", "ACTIVE", null),
                vo(5L, "u5", "李四", "DEPARTED", at),       // 有 name 用 name
                vo(6L, "u6", null, "DEPARTED", at)));        // name 空回退 username
        DepartedInfo info = resolver.resolveDeparted(100L);

        assertEquals(Set.of(5L, 6L), info.departedIds());
        assertEquals("已离开人员·李四·2026-06-10", info.annotations().get(5L));
        assertEquals("已离开人员·u6·2026-06-10", info.annotations().get(6L));
    }

    @Test
    void resolveDeparted_departedAtNull_标注未知() {
        when(rosterService.getRoster(100L)).thenReturn(List.of(
                vo(7L, "u7", null, "DEPARTED", null)));
        DepartedInfo info = resolver.resolveDeparted(100L);

        assertEquals("已离开人员·u7·未知", info.annotations().get(7L));
    }

    @Test
    void intersectDeparted_returnsIntersection() {
        when(rosterService.getRoster(100L)).thenReturn(List.of(
                vo(5L, "u5", null, "DEPARTED", OffsetDateTime.now()),
                vo(6L, "u6", null, "DEPARTED", OffsetDateTime.now())));
        DepartedInfo info = resolver.resolveDeparted(100L);

        // readableAuthors={5,10} ∩ DEPARTED={5,6} → {5}
        assertEquals(Set.of(5L), info.intersectDeparted(Set.of(5L, 10L)));
    }

    @Test
    void intersectDeparted_emptyReadable_returnsEmpty() {
        DepartedInfo info = new DepartedInfo(Set.of(5L), java.util.Map.of(5L, "x"));
        assertTrue(info.intersectDeparted(Set.of()).isEmpty());
        assertTrue(info.intersectDeparted(null).isEmpty());
    }

    @Test
    void intersectDeparted_emptyDeparted_returnsEmpty() {
        DepartedInfo info = DepartedInfo.empty();
        assertTrue(info.intersectDeparted(Set.of(5L)).isEmpty());
    }

    @Test
    void resolveDeparted_emptyRoster_empty() {
        when(rosterService.getRoster(100L)).thenReturn(List.of());
        assertTrue(resolver.resolveDeparted(100L).isEmpty());
    }
}
