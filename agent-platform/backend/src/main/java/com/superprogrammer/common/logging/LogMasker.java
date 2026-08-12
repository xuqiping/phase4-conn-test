package com.superprogrammer.common.logging;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 日志脱敏工具（日志系统 LOG-FR-07）：五类敏感信息正则打码，制度化防回潮。
 *
 * <p>五类：手机号 / 银行卡号 / 身份证号 / 邮箱 / apiKey·token·secret·password·Bearer。
 * 正则全部 {@code static} 预编译；只对最终 message 跑一次转换（{@link MaskingAppender} 逐事件调用），
 * 不逐参数处理，热点路径开销可控。
 *
 * <p><b>评审 checklist（PII 红线 #6）</b>：任何 log 不打印用户输入/记忆/LLM 响应原文，
 * 只打 length/hash/id；脱敏是兜底不是许可。新增敏感类别 → 在此加正则 + {@code LogMaskerTest} 补用例。
 */
public final class LogMasker {

    private LogMasker() {
    }

    /** 规则表：Pattern → 替换串（$1/$2 引用分组）。顺序敏感：身份证先于银行卡（18 位会中银行 13-19 位规则）。 */
    private static final List<Rule> RULES = List.of(
            // 身份证号（18 位，末位可 X）：保前 6 后 4 → 110101********1234
            new Rule(Pattern.compile("(?<!\\d)(\\d{6})\\d{8}(\\d{3}[\\dXx])(?!\\d)"), "$1********$2"),
            // 手机号（1[3-9] 共 11 位）：保前 3 后 4 → 138****8000
            new Rule(Pattern.compile("(?<!\\d)(1[3-9]\\d)\\d{4}(\\d{4})(?!\\d)"), "$1****$2"),
            // 银行卡号（13~19 位连续数字）：保前 4 后 4 → 6228****1234
            new Rule(Pattern.compile("(?<!\\d)(\\d{4})\\d{5,11}(\\d{4})(?!\\d)"), "$1****$2"),
            // 邮箱：本地段保首字符 → a***@example.com
            new Rule(Pattern.compile("([A-Za-z0-9._%+-])[A-Za-z0-9._%+-]*(@[A-Za-z0-9.-]+\\.[A-Za-z]{2,})"), "$1***$2"),
            // apiKey/token/secret/password/authorization=xxx（kv 形态）：值全掩 → apiKey=****
            // 值须含数字（lookahead 防误吞 "Authorization Bearer xxx" 里的 Bearer 单词——Bearer 规则单列）
            new Rule(Pattern.compile("(?i)(api[-_]?key|token|secret|password|authorization)([\"'\\s:=]+)(?=[A-Za-z0-9._\\-+/=]*\\d)[A-Za-z0-9._\\-+/=]{6,}"), "$1$2****"),
            // Bearer xxx：→ Bearer ****
            new Rule(Pattern.compile("(?i)bearer\\s+[A-Za-z0-9._\\-+/=]{6,}"), "Bearer ****")
    );

    private record Rule(Pattern pattern, String replacement) {
    }

    /** 对一段日志文本执行全部脱敏规则；无命中返回原串。 */
    public static String mask(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String result = text;
        for (Rule rule : RULES) {
            result = rule.pattern().matcher(result).replaceAll(rule.replacement());
        }
        return result;
    }
}
