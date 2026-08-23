// agent-platform/backend/src/main/java/com/superprogrammer/auth/controller/AuthPublicConfigController.java
package com.superprogrammer.auth.controller;

import com.superprogrammer.auth.service.AuthChannelSettingService;
import com.superprogrammer.common.result.R;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证通道公开配置（前端登录页渲染依赖）。
 *
 * <p>公开端点：无需登录。返回各通道「是否开启」的布尔标志，**绝不泄露密钥/模板号等敏感配置**
 * （密钥零入库红线 + 最小信息暴露原则）。
 *
 * <p>前端据此决定登录页显示哪些 Tab/按钮：账密（恒开）/ 手机验证码 / 微信扫码 / 找回密码渠道选择。
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthPublicConfigController {

    private final AuthChannelSettingService channelSettingService;

    /** 微信通道开关：仅当 app.auth.wechat.enabled=true 时 WechatMpConfig Bean 才加载。 */
    @Value("${app.auth.wechat.enabled:false}")
    private boolean wechatEnabled;

    /** 返回各通道开关（无敏感字段）。 */
    @GetMapping("/channels")
    public ResponseEntity<R<AuthChannelsVO>> channels() {
        AuthChannelsVO vo = new AuthChannelsVO();
        // 账密登录恒开（users.password 仍存在，无独立开关）
        vo.setPasswordEnabled(true);
        // 邮箱通道：DB 配置优先，环境变量兜底
        vo.setEmailEnabled(channelSettingService.mailSnapshot().enabled());
        // 短信通道：DB 配置优先，环境变量兜底
        vo.setSmsEnabled(channelSettingService.smsSnapshot().enabled());
        // 微信通道：仅环境变量开关（无 DB 配置）
        vo.setWechatEnabled(wechatEnabled);
        // 12x 开关回退：注册邮箱验证码是否强制（前端注册弹窗显隐验证码行）
        vo.setRegisterEmailCodeRequired(channelSettingService.isEmailVerificationRequired());
        return ResponseEntity.ok(R.ok(vo));
    }

    /** 通道开关 VO（仅布尔标志，无密钥）。 */
    @Data
    public static class AuthChannelsVO {
        /** 账号密码登录（恒 true）。 */
        private boolean passwordEnabled = true;
        /** 邮箱通道（注册验证邮件 / 找回密码邮件）。 */
        private boolean emailEnabled;
        /** 短信通道（手机验证码登录 / 找回密码短信）。 */
        private boolean smsEnabled;
        /** 微信扫码登录通道。 */
        private boolean wechatEnabled;
        /** 12x：注册是否强制邮箱验证码（邮箱验证总开关）。 */
        private boolean registerEmailCodeRequired;
    }
}
