// agent-platform/backend/src/main/java/com/superprogrammer/common/security/sig/InjectionSignatureLibrary.java
package com.superprogrammer.common.security.sig;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Prompt 越狱特征库（11x 加固 · P3-C9）：已知越狱模板正则 + 指令式关键短语，static 预编译。
 *
 * <p>防误报设计：只匹配<b>指令式短语</b>（「忽略上述指令」「ignore previous instructions」），
 * 不匹配单纯词频（用户讨论「prompt injection」概念不命中）。命中即 MEDIUM 只记录不阻断。</p>
 */
public final class InjectionSignatureLibrary {

    private InjectionSignatureLibrary() {
    }

    /** 越狱特征（指令式短语，大小写不敏感）。 */
    private static final List<Pattern> PATTERNS = List.of(
            // 中文指令式覆盖
            Pattern.compile("忽略(上述|以上|之前|先前|前面)的?(所有|全部)?(指令|指示|提示|规则|prompt)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("你现在是(一个|一名)?(新的|没有限制|不受限制|无审查|开发者模式)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(把|请?将)你的?(系统|system)?(提示词|prompt|指令)(发|告诉|展示|打印|泄露)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("进入(开发者|调试|维护|上帝)模式", Pattern.CASE_INSENSITIVE),
            // 英文指令式覆盖
            Pattern.compile("ignore\\s+(all\\s+|the\\s+)?(previous|above|prior)\\s+(instructions?|prompts?|rules?)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bDAN\\b.{0,30}(jailbreak|do anything now)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bjailbreak\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(show|reveal|print|leak|repeat)\\s+(me\\s+)?(your\\s+|the\\s+)?(system\\s+prompt|initial\\s+instructions?)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("developer\\s+mode\\s+(enabled|on|activated)", Pattern.CASE_INSENSITIVE)
    );

    /**
     * 扫描消息内容。返命中特征描述（日志用，截断）；未命中返 null。
     * 内容超长只扫前 4000 字符（性能 + 注入必在开头引导）。
     */
    public static String match(String content) {
        if (content == null || content.isBlank()) {
            return null;
        }
        String c = content.length() > 4000 ? content.substring(0, 4000) : content;
        for (Pattern p : PATTERNS) {
            var m = p.matcher(c);
            if (m.find()) {
                String hit = m.group();
                return p.pattern().substring(0, Math.min(40, p.pattern().length()))
                        + " <= " + (hit.length() > 60 ? hit.substring(0, 60) : hit);
            }
        }
        return null;
    }
}
