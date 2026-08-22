package com.superprogrammer.auth.service.mail;

import com.superprogrammer.auth.service.AuthChannelSettingService;

/**
 * 邮件发送通道（12x 运输层抽象）：阿里云 DM 与通用 SMTP 可插拔。
 * <p>实现类无状态——每次发送从调用方传入的配置快照取参数，配置页改完即时生效（不缓存连接）。</p>
 */
public interface MailSender {

    /** 通道标识：与配置键 auth.channel.mail.provider 取值一致（ALIYUN / SMTP）。 */
    String provider();

    /**
     * 发信。失败不抛异常——记日志返回 false（发信不阻断注册/找回主链，语义与历史一致）。
     *
     * @param config   邮件通道配置快照（DB 优先、env 兜底）
     * @param toEmail  收件人
     * @param subject  主题
     * @param htmlBody HTML 正文
     * @return 是否发送成功
     */
    boolean send(AuthChannelSettingService.MailSnapshot config, String toEmail, String subject, String htmlBody);
}
