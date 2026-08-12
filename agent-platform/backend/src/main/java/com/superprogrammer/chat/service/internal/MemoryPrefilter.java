package com.superprogrammer.chat.service.internal;

import com.superprogrammer.system.service.SystemSettingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 计划12 · C · 前置过滤（不调 LLM，按侧独立判定）。
 * <p>
 * 写记忆 turn 前先按侧过滤——避免无信息侧浪费一次生成 LLM、避免敏感信息入库。
 * <b>各侧独立跳过</b>（总体设计 §3.1）：
 * <ul>
 *   <li><b>INPUT 侧</b>：过短 / 纯标点空白 / 纯语气词 / 纯确认 / 致谢 → 仅跳 INPUT 生成。</li>
 *   <li><b>OUTPUT 侧</b>：空 / 短回复含套话 / 免责声明 → 仅跳 OUTPUT 生成
 *       （避免 AI 尾部客套连坐丢失 INPUT 事实——两侧独立判，跳 OUTPUT 不影响 INPUT）。</li>
 *   <li><b>敏感黑名单</b>：密码 / 支付 / 验证码 / 身份证 / 银行卡 / token 六类核心项
 *       <b>预置默认开、不可清空</b>，用户只能在其上加项（{@code memory.prefilter.blacklist-extra}）。
 *       命中即跳该侧。</li>
 * </ul>
 * <p>
 * <b>下游决策</b>（由 MemoryGenerationService 解释）：
 * <ul>
 *   <li>两侧都跳 → 不调生成 LLM 也不写 raw。</li>
 *   <li>仅一侧跳 → 调一次生成 LLM 产出未跳侧。</li>
 *   <li>双侧未跳 → 一次 LLM 出双方三层。</li>
 * </ul>
 *
 * @see SystemSettingService#getMemoryPrefilterBlacklistExtra() 用户加项黑名单
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemoryPrefilter {

    /** INPUT 最短有效长度（trim 后字符数）。低于此 = 过短。 */
    private static final int MIN_INPUT_LEN = 2;
    /** OUTPUT 套话/免责判定长度上限：超过此长度不判套话（长回复可能混杂有效内容，留给生成 LLM）。 */
    private static final int BOILERPLATE_MAX_LEN = 80;

    /** INPUT 纯语气词字符集（整串仅由这些字符组成 → 语气词，如「嗯嗯好的」「行行」）。 */
    private static final String FILLER_CHARS = "嗯啊哦哈呃嗷唉哎的吧么呢呀哇哟噢唔诶额好行可以okokay";

    /** INPUT 纯确认：整串（去标点空白后）仅由确认语拼接而成（如「是的确认了」「收到明白」）。 */
    private static final Pattern CONFIRM_ONLY = compile(
            "^(是(的)?|对(的)?|确认(了)?|收到|明白(了)?|知道(了)?|懂了|行|可以|yes|ok|okay|yeah)+$");

    /** INPUT 致谢：整串仅由致谢语组成（如「谢谢你了」「多谢」）。 */
    private static final Pattern THANKS_ONLY = compile(
            "^(谢谢(你)?(了)?|感谢(你)?|多谢|谢了|thanks|thank\\s*you|thx|3q)+$");

    /** OUTPUT 套话标记（短回复命中 → 跳 OUTPUT）。 */
    private static final List<Pattern> OUTPUT_BOILERPLATE = List.of(
            compile("很高兴为您(服务|效劳)"),
            compile("还有(什么|没有)可以帮(您|你)"),
            compile("还有需要帮忙的?吗"),
            compile("随时为您(服务|解答)"),
            compile("希望(对你|对您)有帮助"),
            compile("is there anything else"),
            compile("can i help"),
            compile("have a (nice|great) day"),
            compile("祝(您|你)(生活愉快|好运|一切顺利|使用愉快)"));

    /** OUTPUT 免责声明标记（短回复命中 → 跳 OUTPUT）。 */
    private static final List<Pattern> OUTPUT_DISCLAIMER = List.of(
            compile("作为一?[名个]?\\s*(ai|人工智能)"),
            compile("我是(一个)?\\s*ai"),
            compile("i am an? ai"),
            compile("as an? ai"),
            compile("(无法|不能)(为您?|为用户)?提供"),
            compile("请咨询(相关)?专业人士"),
            compile("不代表专业意见"),
            compile("免责声明|disclaimer"));

    /** 核心敏感黑名单（六类，预置不可清空）。命中即跳该侧。 */
    private static final List<Pattern> CORE_BLACKLIST = List.of(
            compile("password|passwd|passcode|密码|口令|登录密钥"),
            compile("支付|付款|转账|alipay|wechat\\s*pay|微信支付|支付宝"),
            compile("验证码|verification\\s*code|otp|captcha|动态码|sms\\s*code|短信验证"),
            compile("身份证|id\\s*card|身份证号|身份证号码|身份证明|护照号"),
            compile("银行卡|bank\\s*card|信用卡|借记卡|卡号|card\\s*number|cvv"),
            compile("token|bearer|api[-_]?key|apikey|secret[-_]?key|access[-_]?key|密钥|私钥|access\\s*token"));

    private final SystemSettingService systemSettingService;

    /**
     * 前置过滤结果。
     *
     * @param skipInput    是否跳过 INPUT 侧生成
     * @param skipOutput   是否跳过 OUTPUT 侧生成
     * @param inputReason  INPUT 跳过原因（null = 未跳）；用于审计/日志
     * @param outputReason OUTPUT 跳过原因（null = 未跳）
     */
    public record FilterResult(boolean skipInput, boolean skipOutput,
                               String inputReason, String outputReason) {
        /** 两侧都跳 → 不调生成 LLM 也不写 raw。 */
        public boolean bothSkipped() {
            return skipInput && skipOutput;
        }

        /** 至少一侧跳 → 走单侧生成或全跳。 */
        public boolean anySkipped() {
            return skipInput || skipOutput;
        }
    }

    /**
     * 敏感黑名单专扫（记忆二期 P1 路由蒸馏二次扫描用，设计 §9-16）。
     * 只跑核心六类 + 用户加项，不跑 INPUT/OUTPUT 侧规则（过短/语气词等不是敏感信号）。
     *
     * @return true = 命中黑名单（调用方按场景降级，如条目降 PENDING_REVIEW）
     */
    public boolean hitsBlacklist(String text) {
        return blacklistReason(text) != null;
    }

    /**
     * 按 INPUT/OUTPUT 各自规则独立过滤。
     *
     * @param userInput       用户本轮输入（null → 视为空，跳 INPUT）
     * @param assistantOutput 助手本轮回复（null → 视为空，跳 OUTPUT）
     * @return 过滤结果（两侧独立判定）
     */
    public FilterResult filter(String userInput, String assistantOutput) {
        String in = nullToEmpty(userInput);
        String out = nullToEmpty(assistantOutput);

        String inReason = inputSkipReason(in);
        if (inReason == null) {
            inReason = blacklistReason(in);
        }

        String outReason = outputSkipReason(out);
        if (outReason == null) {
            outReason = blacklistReason(out);
        }

        if ((inReason != null || outReason != null)) {
            log.debug("前置过滤命中 inputSkip={}({}) outputSkip={}({})",
                    inReason != null, inReason, outReason != null, outReason);
        }
        return new FilterResult(inReason != null, outReason != null, inReason, outReason);
    }

    // ---------- INPUT 侧规则 ----------

    private String inputSkipReason(String s) {
        String trimmed = s.trim();
        if (trimmed.length() < MIN_INPUT_LEN) {
            return "过短";
        }
        String norm = stripPunctSpace(trimmed).toLowerCase();
        if (norm.isEmpty()) {
            return "纯标点/空白";
        }
        if (isFillerOnly(norm)) {
            return "语气词";
        }
        if (CONFIRM_ONLY.matcher(norm).matches()) {
            return "纯确认";
        }
        if (THANKS_ONLY.matcher(norm).matches()) {
            return "致谢";
        }
        return null;
    }

    /** 整串仅由语气词字符组成（如「嗯嗯好的」→ 嗯/嗯/好/的 全在 FILLER_CHARS）。 */
    private boolean isFillerOnly(String norm) {
        if (norm.isEmpty()) {
            return false;
        }
        for (int i = 0; i < norm.length(); i++) {
            if (FILLER_CHARS.indexOf(norm.charAt(i)) < 0) {
                return false;
            }
        }
        return true;
    }

    // ---------- OUTPUT 侧规则 ----------

    private String outputSkipReason(String s) {
        String trimmed = s.trim();
        if (trimmed.isEmpty()) {
            return "空回复";
        }
        // 短回复才判套话/免责（长回复可能混杂有效内容，留给生成 LLM）
        if (trimmed.length() <= BOILERPLATE_MAX_LEN) {
            for (Pattern p : OUTPUT_BOILERPLATE) {
                if (p.matcher(trimmed).find()) {
                    return "套话";
                }
            }
            for (Pattern p : OUTPUT_DISCLAIMER) {
                if (p.matcher(trimmed).find()) {
                    return "免责";
                }
            }
        }
        return null;
    }

    // ---------- 敏感黑名单（核心 + 用户加项） ----------

    /** 命中核心项 → 「敏感信息」；命中用户加项 → 「敏感信息（用户加项）」；未命中 → null。 */
    private String blacklistReason(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        for (Pattern p : CORE_BLACKLIST) {
            if (p.matcher(text).find()) {
                return "敏感信息";
            }
        }
        // 用户加项（在其上加，核心项已预置不可清空）
        List<String> extra = systemSettingService.getMemoryPrefilterBlacklistExtra();
        if (extra != null && !extra.isEmpty()) {
            String lower = text.toLowerCase();
            for (String term : extra) {
                if (term != null && !term.isBlank() && lower.contains(term.toLowerCase())) {
                    return "敏感信息（用户加项）";
                }
            }
        }
        return null;
    }

    // ---------- 工具 ----------

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    /** 去除标点/空白，仅保留汉字 + 字母数字（用于规则匹配的归一化形态）。 */
    private static String stripPunctSpace(String s) {
        return s.replaceAll("[^\\p{IsHan}A-Za-z0-9]", "");
    }

    private static Pattern compile(String regex) {
        return Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }
}
