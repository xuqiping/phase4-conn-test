// agent-platform/backend/src/main/java/com/superprogrammer/common/security/InjectionDetector.java
package com.superprogrammer.common.security;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 注入特征检测器（11x 加固 · P2-C5）：纯函数静态工具，正则启动期预编译（非每请求编译）。
 *
 * <p><b>误报防线</b>：特征用「组合模式」（如 union+select 两词同现、`or 1=1` 恒真式），
 * 不匹配单字符（英文逗号/单引号正常输入合法）。payload 先 URL decode（双重编码 %25 也解一次）+ 小写。
 *
 * <p><b>不扫业务正文</b>：调用方（SecurityGateFilter）只喂 URL query + 表单参数键值，
 * chat 消息内容不扫（AI 对话走 P3 PROMPT_INJECTION 冷规则）。</p>
 */
public final class InjectionDetector {

    private InjectionDetector() {
    }

    /** 命中类型：SQLI_PROBE / XSS_PROBE / PATH_PROBE（与 SecurityEventTypes 对齐）。 */
    public record Hit(String eventType, String snippet) {
    }

    // ---- SQLi 组合特征（两词同现/恒真式/注释截断/危险函数） ----
    private static final List<Pattern> SQLI_PATTERNS = List.of(
            Pattern.compile("\\bunion\\b[\\s/\\*]+(all[\\s/\\*]+)?select\\b"),
            Pattern.compile("\\bselect\\b.*\\bfrom\\b.*\\binformation_schema\\b"),
            Pattern.compile("('|\"|\\))\\s*(or|and)\\s+\\(?\\s*([\\d'\"])\\s*=\\s*\\3"),   // ' or 1=1 / " or "a"="a"
            Pattern.compile("('|\\))\\s*(or|and)\\s+('\\w+'|'\\d+')\\s*=\\s*'"),            // ' or 'a'='a
            Pattern.compile(";\\s*(drop|truncate|delete\\s+from|insert\\s+into|update\\s+\\w+\\s+set)\\b"),
            Pattern.compile("--[\\s\\)]"),                                                  // 注释截断（-- 后跟空白/右括号）
            Pattern.compile("/\\*!"),                                                       // mysql 内联执行注释
            Pattern.compile("\\b(sleep|benchmark|pg_sleep|load_file)\\s*\\("),
            Pattern.compile("\\bexec\\s*\\(\\s*xp_"),                                       // mssql xp_cmdshell
            Pattern.compile("\\bextractvalue\\s*\\(|\\bupdatexml\\s*\\(|\\bconcat\\s*\\(.*0x") // 报错注入
    );

    // ---- XSS 特征 ----
    private static final List<Pattern> XSS_PATTERNS = List.of(
            Pattern.compile("<\\s*script\\b"),
            Pattern.compile("<\\s*(iframe|object|embed|svg\\s+onload)\\b"),
            Pattern.compile("javascript\\s*:"),
            Pattern.compile("\\bon(error|load|click|focus|mouseover)\\s*="),
            Pattern.compile("<\\s*img[^>]*\\bonerror\\s*="),
            Pattern.compile("document\\s*\\.\\s*(cookie|location)"),
            Pattern.compile("\\beval\\s*\\(\\s*(atob|unescape|fromcharcode)")
    );

    // ---- 路径穿越特征 ----
    private static final List<Pattern> PATH_PATTERNS = List.of(
            Pattern.compile("(\\.\\./|\\.\\.\\\\){1,}"),                                    // ../ ..\
            Pattern.compile("(^|[/\\\\])etc[/\\\\]passwd"),
            Pattern.compile("(^|[/\\\\])boot\\.ini"),
            Pattern.compile("%00"),                                                          // null byte 截断
            Pattern.compile("(^|[/\\\\])proc[/\\\\]self[/\\\\]")
    );

    /**
     * 扫描单个参数片段。返回命中（类型+截断片段，片段≤80字符且只保留可见 ASCII/常见符号，防日志污染）。
     * 未命中返回 null。
     */
    public static Hit detect(String raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        String payload = decodeTwice(raw).toLowerCase();
        if (payload.isEmpty()) {
            return null;
        }
        for (Pattern p : SQLI_PATTERNS) {
            if (p.matcher(payload).find()) {
                return new Hit(SecurityEventTypes.SQLI_PROBE, sanitize(payload));
            }
        }
        for (Pattern p : XSS_PATTERNS) {
            if (p.matcher(payload).find()) {
                return new Hit(SecurityEventTypes.XSS_PROBE, sanitize(payload));
            }
        }
        for (Pattern p : PATH_PATTERNS) {
            if (p.matcher(payload).find()) {
                return new Hit(SecurityEventTypes.PATH_PROBE, sanitize(payload));
            }
        }
        return null;
    }

    /** URL decode 最多两次（双重编码绕过 %2527 → %27 → '）。解码失败原样返回（不挡检测）。 */
    static String decodeTwice(String raw) {
        String result = raw;
        for (int i = 0; i < 2; i++) {
            try {
                String decoded = URLDecoder.decode(result, StandardCharsets.UTF_8);
                if (decoded.equals(result)) {
                    break;
                }
                result = decoded;
            } catch (IllegalArgumentException e) {
                break; // 非法 % 序列（正常用户也可能输入 %）→ 用当前已解码结果继续
            }
        }
        return result;
    }

    /** 片段脱敏截断：≤80 字符 + 去控制字符（防攻击者往日志/事件里塞 ANSI 逃逸）。 */
    static String sanitize(String payload) {
        String cleaned = payload.replaceAll("[\\p{Cntrl}&&[^\\n\\t]]", "?");
        return cleaned.length() > 80 ? cleaned.substring(0, 80) : cleaned;
    }
}
