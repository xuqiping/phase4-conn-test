package com.superprogrammer.chat.service.internal;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * M2 时间线记忆:value schema 解析/拼接 helper。
 * <p>
 * schema = 标量字符串 + 分号分段 + 行首 ISO 日期前缀。时序事实(住址/工作/状态)value 存
 * {@code 2026-06-25 住萧山;2027-01-01 住拱墅}(段间裸分号,parse 时 trim);非时序事实(名字/偏好)维持单值(无日期前缀)。
 * <p>
 * 仅处理「时序分支」——非时序分支(中文逗号 join)仍由 MemoryConflictService.joinDistinct 负责,
 * 本类不动它。存量兼容:老 value 无日期前缀 → parse 当 undated 单段,不破坏。
 *
 * <h3>设计要点</h3>
 * <ul>
 *   <li>日期前缀仅认 {@code YYYY-MM-DD}(严格 ISO_LOCAL_DATE),完整时间戳(含 T)不当日期剥离。</li>
 *   <li>joinSorted:dated 段按日期升序,undated 段保原序附后,段间裸 {@code ";"} 分隔(parse 时 trim)。</li>
 *   <li>mergeTemporal:old 各段 + new(newTs 日期)合并去重(同 date+同 content 去重)后排序拼。</li>
 * </ul>
 */
public final class MemoryValueTimeline {

    /** 单段:可选 ISO 日期 + 内容。date=null = 无日期(老数据单值或非时序)。 */
    public record Segment(LocalDate date, String content) {}

    /** parse 结果:持段列表 + rejoin 便捷方法。 */
    public static final class ParsedValue {
        private final List<Segment> segments;
        public ParsedValue(List<Segment> segments) { this.segments = segments; }
        public List<Segment> segments() { return segments; }
        public boolean hasDated() { return segments.stream().anyMatch(s -> s.date() != null); }
        /** 重新排序拼回 value 字符串(dated 升序 + undated 附后)。 */
        public String rejoin() { return joinSorted(segments); }
    }

    private static final Pattern DATED_PREFIX = Pattern.compile("^(\\d{4}-\\d{2}-\\d{2})\\s+(.+)$", Pattern.DOTALL);
    private static final DateTimeFormatter ISO_DATE = DateTimeFormatter.ISO_LOCAL_DATE;

    private MemoryValueTimeline() {}

    /** value → 段列表。null/空白 → 空。 */
    public static ParsedValue parse(String value) {
        List<Segment> segs = new ArrayList<>();
        if (value == null || value.isBlank()) return new ParsedValue(segs);
        for (String raw : value.split(";")) {
            String part = raw.trim();
            if (part.isEmpty()) continue;
            segs.add(parseSegment(part));
        }
        return new ParsedValue(segs);
    }

    private static Segment parseSegment(String part) {
        Matcher m = DATED_PREFIX.matcher(part);
        if (m.matches()) {
            try {
                LocalDate d = LocalDate.parse(m.group(1), ISO_DATE);
                return new Segment(d, m.group(2).trim());
            } catch (RuntimeException ignored) {
                // 非法日期(如 2026-13-45)→ 当 undated 原样保留
            }
        }
        return new Segment(null, part);
    }

    /** dated 段按日期升序,undated 段保原序附后,段间 "; " 分隔。 */
    public static String joinSorted(List<Segment> segments) {
        if (segments == null || segments.isEmpty()) return "";
        List<Segment> dated = new ArrayList<>();
        List<Segment> undated = new ArrayList<>();
        for (Segment s : segments) {
            if (s == null || s.content() == null || s.content().isBlank()) continue;
            if (s.date() != null) dated.add(s);
            else undated.add(s);
        }
        dated.sort(Comparator.comparing(Segment::date));
        List<String> parts = new ArrayList<>();
        for (Segment s : dated) parts.add(s.date().format(ISO_DATE) + " " + s.content());
        for (Segment s : undated) parts.add(s.content());
        return String.join(";", parts);
    }

    /** content 前缀 newTs 的 ISO 日期 → "YYYY-MM-DD content"。 */
    public static String withDatePrefix(String content, OffsetDateTime newTs) {
        if (content == null) content = "";
        String date = (newTs == null ? LocalDate.now() : newTs.toLocalDate()).format(ISO_DATE);
        return date + " " + content.trim();
    }

    /**
     * 时序 merge:old 各段 + new(newTs 日期前缀)合并,去重(同 date+content)后排序拼。
     * old 可为 null/空 → 仅 new。new content 若与 old 某 dated 段同 date+content → 去重。
     */
    public static String mergeTemporal(String oldValue, String newValue, OffsetDateTime newTs) {
        List<Segment> segs = new ArrayList<>(parse(oldValue).segments());
        if (newValue != null && !newValue.isBlank()) {
            LocalDate d = newTs == null ? LocalDate.now() : newTs.toLocalDate();
            segs.add(new Segment(d, newValue.trim()));
        }
        // 去重保序(LinkedHashSet on record = date+content 全等才算重复)
        Set<Segment> dedup = new LinkedHashSet<>(segs);
        return joinSorted(new ArrayList<>(dedup));
    }

    /** value 含至少一个 dated 段 → true(panel 据此判是否时间线展示)。 */
    public static boolean isTimelineValue(String value) {
        return parse(value).hasDated();
    }
}
