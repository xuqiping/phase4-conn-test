package com.superprogrammer.chat.service.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import com.superprogrammer.system.service.SystemSettingService;

/**
 * 计划12 · C · 前置过滤单测（Mockito 无 DB/LLM）。
 * 覆盖：按侧独立跳过（INPUT/OUTPUT 各自规则）+ 敏感黑名单命中跳该侧 + 用户加项黑名单。
 * 出口对齐 plan C：前置过滤按侧独立跳过 / 敏感黑名单命中跳过该侧。
 */
@ExtendWith(MockitoExtension.class)
class MemoryPrefilterTest {

    @Mock
    private SystemSettingService systemSettingService;

    @InjectMocks
    private MemoryPrefilter prefilter;

    /** 默认场景：用户未加项黑名单 → 空集（核心项仍由 Prefilter 内置）。 */
    private MemoryPrefilter.FilterResult filter(String in, String out) {
        return prefilter.filter(in, out);
    }

    // ---------- INPUT 侧独立规则 ----------

    @Test
    @DisplayName("INPUT 过短：仅跳 INPUT，OUTPUT 不受影响")
    void inputTooShort_skipsInputOnly() {
        var r = filter("好", "我今天住到了萧山区地铁沿线");
        assertTrue(r.skipInput(), "过短 INPUT 应跳过");
        assertFalse(r.skipOutput(), "OUTPUT 不受 INPUT 规则影响");
        assertEquals("过短", r.inputReason());
    }

    @Test
    @DisplayName("INPUT 纯标点/空白：跳 INPUT")
    void inputPurePunctuation_skipsInput() {
        var r = filter("。。。 ！！ ??", "正常回复");
        assertTrue(r.skipInput());
        assertEquals("纯标点/空白", r.inputReason());
    }

    @Test
    @DisplayName("INPUT 纯语气词：跳 INPUT")
    void inputFillerOnly_skipsInput() {
        var r = filter("嗯嗯好的", "正常回复");
        assertTrue(r.skipInput());
        assertEquals("语气词", r.inputReason());
    }

    @Test
    @DisplayName("INPUT 纯确认：跳 INPUT")
    void inputPureConfirm_skipsInput() {
        var r = filter("是的，确认了。", "正常回复");
        assertTrue(r.skipInput());
        assertEquals("纯确认", r.inputReason());
    }

    @Test
    @DisplayName("INPUT 致谢：跳 INPUT")
    void inputThanks_skipsInput() {
        var r = filter("谢谢你了！", "正常回复");
        assertTrue(r.skipInput());
        assertEquals("致谢", r.inputReason());
    }

    @Test
    @DisplayName("INPUT 含事实（确认+后续内容）：不跳")
    void inputConfirmPlusFact_notSkipped() {
        var r = filter("是的，我住萧山区", "好的");
        assertFalse(r.skipInput(), "确认词后跟事实不应整体跳过");
    }

    @Test
    @DisplayName("INPUT 正常事实：两侧都不跳")
    void inputNormalFact_neitherSkipped() {
        var r = filter("我住萧山区地铁沿线", "已记录");
        assertFalse(r.skipInput());
        assertFalse(r.skipOutput());
    }

    // ---------- OUTPUT 侧独立规则 ----------

    @Test
    @DisplayName("OUTPUT 纯套话：仅跳 OUTPUT，INPUT 保留（防尾部客套连坐丢事实）")
    void outputBoilerplate_skipsOutputOnly() {
        var r = filter("我住萧山区", "很高兴为您服务，还有什么可以帮您的吗？");
        assertFalse(r.skipInput(), "INPUT 事实必须保留");
        assertTrue(r.skipOutput(), "纯套话 OUTPUT 应跳过");
        assertEquals("套话", r.outputReason());
    }

    @Test
    @DisplayName("OUTPUT 免责声明：跳 OUTPUT")
    void outputDisclaimer_skipsOutput() {
        var r = filter("正常问题", "作为一个 AI，我无法提供医疗建议，请咨询专业人士。");
        assertTrue(r.skipOutput());
        assertEquals("免责", r.outputReason());
    }

    @Test
    @DisplayName("OUTPUT 正常回复：不跳")
    void outputNormal_notSkipped() {
        var r = filter("帮我写爬虫", "好的，用 requests + BeautifulSoup4，含重试逻辑的示例代码如下……");
        assertFalse(r.skipOutput());
    }

    // ---------- 敏感黑名单（核心项预置，命中跳该侧） ----------

    @Test
    @DisplayName("INPUT 命中密码：跳 INPUT（敏感黑名单）")
    void inputPassword_hitsBlacklist() {
        var r = filter("我的密码是 abc123456", "已收到");
        assertTrue(r.skipInput());
        assertEquals("敏感信息", r.inputReason());
    }

    @Test
    @DisplayName("INPUT 命中验证码：跳 INPUT")
    void inputOtp_hitsBlacklist() {
        var r = filter("验证码是 882134", "好的");
        assertTrue(r.skipInput());
        assertEquals("敏感信息", r.inputReason());
    }

    @Test
    @DisplayName("INPUT 命中身份证：跳 INPUT")
    void inputIdCard_hitsBlacklist() {
        var r = filter("身份证号 330109199001011234", "好的");
        assertTrue(r.skipInput());
        assertEquals("敏感信息", r.inputReason());
    }

    @Test
    @DisplayName("INPUT 命中银行卡：跳 INPUT")
    void inputBankCard_hitsBlacklist() {
        var r = filter("银行卡号 6222020200112345678", "好的");
        assertTrue(r.skipInput());
        assertEquals("敏感信息", r.inputReason());
    }

    @Test
    @DisplayName("OUTPUT 命中 token：跳 OUTPUT（黑名单按侧独立）")
    void outputToken_hitsBlacklist() {
        var r = filter("正常问题", "你的 api-key 是 sk-abcdef123456，请妥善保管");
        assertTrue(r.skipOutput(), "黑名单按侧独立命中");
        assertEquals("敏感信息", r.outputReason());
    }

    @Test
    @DisplayName("黑名单大小写不敏感：PAY 命中支付")
    void blacklistCaseInsensitive() {
        var r = filter("PAY me via alipay", "ok");
        assertTrue(r.skipInput());
        assertEquals("敏感信息", r.inputReason());
    }

    // ---------- 用户加项黑名单（只能加，不能清核心项） ----------

    @Test
    @DisplayName("用户加项黑名单命中：跳该侧")
    void userExtraBlacklist_hits() {
        when(systemSettingService.getMemoryPrefilterBlacklistExtra())
                .thenReturn(java.util.List.of("体检报告", "薪资"));
        var r = filter("我的薪资是 50 万", "好的");
        assertTrue(r.skipInput());
        assertEquals("敏感信息（用户加项）", r.inputReason());
    }

    @Test
    @DisplayName("用户加项为空：核心项仍生效（不能清空核心）")
    void userExtraEmpty_coreStillWorks() {
        when(systemSettingService.getMemoryPrefilterBlacklistExtra())
                .thenReturn(java.util.List.of());
        var r = filter("密码 p@ssw0rd", "好的");
        assertTrue(r.skipInput());
        assertEquals("敏感信息", r.inputReason());
    }

    // ---------- 组合态 ----------

    @Test
    @DisplayName("两侧都跳：bothSkipped=true（不调 LLM 不写 raw）")
    void bothSkipped() {
        var r = filter("嗯", "很高兴为您服务");
        assertTrue(r.skipInput());
        assertTrue(r.skipOutput());
        assertTrue(r.bothSkipped());
    }

    @Test
    @DisplayName("仅一侧跳：anySkipped=true, bothSkipped=false")
    void oneSideSkipped() {
        var r = filter("我住萧山", "很高兴为您服务");
        assertFalse(r.skipInput());
        assertTrue(r.skipOutput());
        assertTrue(r.anySkipped());
        assertFalse(r.bothSkipped());
    }

    @Test
    @DisplayName("空入参：两侧都跳（不崩）")
    void nullInputs_bothSkipped() {
        var r = filter(null, null);
        assertTrue(r.bothSkipped());
    }
}
