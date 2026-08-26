package com.superprogrammer.common.audit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 中文标签字典测试（问题修复 #2 显示层）。
 * 已知码命中中文、未知码原样回落、null 安全。
 */
class AuditLabelDictionaryTest {

    @Test
    void moduleLabel_knownCode_returnsChinese() {
        assertEquals("媒体生成", AuditLabelDictionary.moduleLabel("media"));
        assertEquals("智能对话", AuditLabelDictionary.moduleLabel("chat"));
        assertEquals("认证", AuditLabelDictionary.moduleLabel("auth"));
    }

    @Test
    void moduleLabel_unknownCode_fallsBackRaw() {
        assertEquals("something_new", AuditLabelDictionary.moduleLabel("something_new"));
    }

    @Test
    void moduleLabel_null_returnsNull() {
        assertNull(AuditLabelDictionary.moduleLabel(null));
    }

    @Test
    void actionLabel_knownPair_returnsChinese() {
        assertEquals("视频生成成功", AuditLabelDictionary.actionLabel("media", "video_gen_success"));
        assertEquals("发送消息", AuditLabelDictionary.actionLabel("chat", "send_message"));
        assertEquals("对话完成", AuditLabelDictionary.actionLabel("chat", "chat_completed"));
        assertEquals("管理员充值", AuditLabelDictionary.actionLabel("billing", "admin_recharge"));
    }

    @Test
    void actionLabel_knownModuleUnknownAction_fallsBackAction() {
        assertEquals("brand_new_action", AuditLabelDictionary.actionLabel("media", "brand_new_action"));
    }

    @Test
    void actionLabel_unknownModule_fallsBackAction() {
        assertEquals("login", AuditLabelDictionary.actionLabel("mystery_module", "login"));
    }

    @Test
    void actionLabel_nullAction_returnsNull() {
        assertNull(AuditLabelDictionary.actionLabel("media", null));
    }

    @Test
    void voWithLabels_populatesBoth() {
        AuditLogEntity e = new AuditLogEntity();
        e.setModule("media");
        e.setAction("image_gen_success");
        AuditLogVO vo = AuditLogVO.from(e).withLabels();
        assertEquals("媒体生成", vo.getModuleLabel());
        assertEquals("图片生成成功", vo.getActionLabel());
    }

    // B1（8x-1）：P0 手工行新码全命中中文
    @Test
    void actionLabel_p0NewAuthCodes_returnsChinese() {
        assertEquals("发注册验证码", AuditLabelDictionary.actionLabel("auth", "send_register_code"));
        assertEquals("绑定TOTP", AuditLabelDictionary.actionLabel("auth", "mfa_bind"));
        assertEquals("确认绑定TOTP", AuditLabelDictionary.actionLabel("auth", "mfa_bind_confirm"));
        assertEquals("解绑TOTP", AuditLabelDictionary.actionLabel("auth", "mfa_unbind"));
    }
}
