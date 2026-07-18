package com.superprogrammer.chat.service.internal;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static com.superprogrammer.chat.service.internal.MemoryValueTimeline.Segment;
import static org.junit.jupiter.api.Assertions.*;

/** M2:MemoryValueTimeline 单测。value schema = 标量 + 分号分段 + 行首 ISO 日期前缀。 */
class MemoryValueTimelineTest {

    @Test
    void parse_plainScalar_singleUndatedSegment() {
        List<Segment> s = MemoryValueTimeline.parse("小美").segments();
        assertEquals(1, s.size());
        assertNull(s.get(0).date());
        assertEquals("小美", s.get(0).content());
    }

    @Test
    void parse_datedSegments_splitBySemicolon() {
        List<Segment> s = MemoryValueTimeline.parse("2026-06-25 住萧山;2027-01-01 住拱墅").segments();
        assertEquals(2, s.size());
        assertEquals(LocalDate.of(2026, 6, 25), s.get(0).date());
        assertEquals("住萧山", s.get(0).content());
        assertEquals(LocalDate.of(2027, 1, 1), s.get(1).date());
        assertEquals("住拱墅", s.get(1).content());
    }

    @Test
    void parse_mixedDatedAndUndated() {
        List<Segment> s = MemoryValueTimeline.parse("2026-06-25 住萧山;无固定居所").segments();
        assertEquals(2, s.size());
        assertNotNull(s.get(0).date());
        assertNull(s.get(1).date());
        assertEquals("无固定居所", s.get(1).content());
    }

    @Test
    void parse_emptyOrNull_emptyList() {
        assertTrue(MemoryValueTimeline.parse(null).segments().isEmpty());
        assertTrue(MemoryValueTimeline.parse("").segments().isEmpty());
        assertTrue(MemoryValueTimeline.parse("  ").segments().isEmpty());
    }

    @Test
    void parse_trimsWhitespace() {
        List<Segment> s = MemoryValueTimeline.parse(" 2026-06-25 住萧山 ;  小美 ").segments();
        assertEquals(2, s.size());
        assertEquals("住萧山", s.get(0).content());
        assertEquals("小美", s.get(1).content());
    }

    @Test
    void parse_fullIsoTimestamp_notTreatedAsDatePrefix() {
        // 仅识别 YYYY-MM-DD 前缀;完整 ISO 时间戳(2026-06-25T08:00)不当作日期前缀剥离
        List<Segment> s = MemoryValueTimeline.parse("2026-06-25T08:00 开会").segments();
        assertEquals(1, s.size());
        // T 在第 11 位,正则只吃 10 位日期+空格 → 整段当 content(date=null)
        assertNull(s.get(0).date());
    }

    @Test
    void join_datedSegments_sortedByDateAsc() {
        String out = MemoryValueTimeline.parse("2027-01-01 住拱墅;2026-06-25 住萧山").rejoin();
        assertEquals("2026-06-25 住萧山;2027-01-01 住拱墅", out);
    }

    @Test
    void join_undatedAppendedAfterDated() {
        String out = MemoryValueTimeline.parse("2026-06-25 住萧山;无固定居所").rejoin();
        assertEquals("2026-06-25 住萧山;无固定居所", out);
    }

    @Test
    void withDatePrefix_prependsIsoDate() {
        OffsetDateTime ts = OffsetDateTime.of(2026, 6, 25, 14, 0, 0, 0, ZoneOffset.UTC);
        assertEquals("2026-06-25 住萧山", MemoryValueTimeline.withDatePrefix("住萧山", ts));
    }

    @Test
    void mergeTemporal_oldDatedPlusNewDated_sortedUnique() {
        // old 已有 2026-06-25 段,new 2027-01-01 段 → 两段按序拼,不丢
        OffsetDateTime newTs = OffsetDateTime.of(2027, 1, 1, 9, 0, 0, 0, ZoneOffset.UTC);
        String out = MemoryValueTimeline.mergeTemporal("2026-06-25 住萧山", "住拱墅", newTs);
        assertEquals("2026-06-25 住萧山;2027-01-01 住拱墅", out);
    }

    @Test
    void mergeTemporal_sameDateSameContent_dedup() {
        OffsetDateTime newTs = OffsetDateTime.of(2026, 6, 25, 9, 0, 0, 0, ZoneOffset.UTC);
        String out = MemoryValueTimeline.mergeTemporal("2026-06-25 住萧山", "住萧山", newTs);
        assertEquals("2026-06-25 住萧山", out);
    }

    @Test
    void mergeTemporal_oldScalarFirstNew_newDatePrepends() {
        // old 无日期段(老数据单值),new 带日期 → old 保原样(null 日期)附后,new 在前
        OffsetDateTime newTs = OffsetDateTime.of(2026, 6, 25, 9, 0, 0, 0, ZoneOffset.UTC);
        String out = MemoryValueTimeline.mergeTemporal("出生在杭州", "住萧山", newTs);
        assertEquals("2026-06-25 住萧山;出生在杭州", out);
    }

    @Test
    void mergeTemporal_nullOld_newOnlyWithDate() {
        OffsetDateTime newTs = OffsetDateTime.of(2026, 6, 25, 9, 0, 0, 0, ZoneOffset.UTC);
        String out = MemoryValueTimeline.mergeTemporal(null, "住萧山", newTs);
        assertEquals("2026-06-25 住萧山", out);
    }

    @Test
    void isTimelineValue_detectsDatedSegment() {
        assertTrue(MemoryValueTimeline.isTimelineValue("2026-06-25 住萧山"));
        assertTrue(MemoryValueTimeline.isTimelineValue("2026-06-25 住萧山;2027-01-01 住拱墅"));
        assertFalse(MemoryValueTimeline.isTimelineValue("小美"));
        assertFalse(MemoryValueTimeline.isTimelineValue(""));
        assertFalse(MemoryValueTimeline.isTimelineValue(null));
    }
}
