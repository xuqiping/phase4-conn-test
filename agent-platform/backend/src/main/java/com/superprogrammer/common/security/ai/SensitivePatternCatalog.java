// agent-platform/backend/src/main/java/com/superprogrammer/common/security/ai/SensitivePatternCatalog.java
package com.superprogrammer.common.security.ai;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LLM 输出侧敏感模式目录（安全体系 S3 · SEC-FR-052 / LLM02 敏感信息泄露）：
 * 网关咽喉对模型文本输出扫描，命中整段替换 {@code ***}（输出面不保留前缀后缀——日志面为排障留
 * 首尾，输出面面向终端用户，全遮蔽口径）。
 *
 * <p>类别与正则和 {@link com.superprogrammer.common.logging.LogMasker} 同源（身份证18/手机号/
 * 银行卡13~19/apiKey·token·secret·password·authorization kv/Bearer）。
 * <b>双向同步红线</b>：LogMasker 增删敏感类别时本目录必须同步评审（日志面可多不可少；
 * 输出面邮件地址不打码——用户自己问自己邮箱属正常诉求，误伤率高）。
 *
 * <p>静态工具类：规则 {@code static} 预编译，无状态线程安全。
 */
public final class SensitivePatternCatalog {

    private SensitivePatternCatalog() {
    }

    /** 替换占位符。 */
    public static final String MASK = "***";

    /**
     * 规则表（顺序敏感：身份证先于银行卡——18 位会命中银行卡 13~19 位规则，先到先得口径一致）。
     * 与 LogMasker 差异：值整段替换，不引用分组。
     */
    private static final List<Pattern> PATTERNS = List.of(
            // 身份证号（18 位，末位可 X）
            Pattern.compile("(?<!\\d)\\d{6}\\d{8}\\d{3}[\\dXx](?!\\d)"),
            // 手机号（1[3-9] 共 11 位）
            Pattern.compile("(?<!\\d)1[3-9]\\d{9}(?!\\d)"),
            // 银行卡号（13~19 位连续数字）
            Pattern.compile("(?<!\\d)\\d{13,19}(?!\\d)"),
            // apiKey/token/secret/password/authorization kv 形态（值须含数字，防吞纯单词）
            Pattern.compile("(?i)(api[-_]?key|token|secret|password|authorization)([\"'\\s:=]+)(?=[A-Za-z0-9._\\-+/=]*\\d)[A-Za-z0-9._\\-+/=]{6,}"),
            // Bearer xxx
            Pattern.compile("(?i)bearer\\s+[A-Za-z0-9._\\-+/=]{6,}")
    );

    /** 是否命中任一敏感模式（不产生替换文本，供流式快速预判）。 */
    public static boolean hits(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        for (Pattern p : PATTERNS) {
            if (p.matcher(text).find()) {
                return true;
            }
        }
        return false;
    }

    /** 全规则替换；无命中返回原串（同引用，零拷贝）。 */
    public static String mask(String text) {
        if (!hits(text)) {
            return text;
        }
        String result = text;
        for (Pattern p : PATTERNS) {
            result = p.matcher(result).replaceAll(Matcher.quoteReplacement(MASK));
        }
        return result;
    }
}
