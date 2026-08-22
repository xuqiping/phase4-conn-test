package com.superprogrammer.auth.service.mail;

import com.aliyuncs.DefaultAcsClient;
import com.aliyuncs.IAcsClient;
import com.aliyuncs.dm.model.v20151123.SingleSendMailRequest;
import com.aliyuncs.dm.model.v20151123.SingleSendMailResponse;
import com.aliyuncs.profile.DefaultProfile;
import com.aliyuncs.profile.IClientProfile;
import com.superprogrammer.auth.service.AuthChannelSettingService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 阿里云 DirectMail 发信通道（原 EmailService.sendMail 逻辑原样搬家，12x 抽象化）。
 */
@Slf4j
@Component
public class AliyunMailSender implements MailSender {

    @Override
    public String provider() {
        return "ALIYUN";
    }

    @Override
    public boolean send(AuthChannelSettingService.MailSnapshot config, String toEmail, String subject, String htmlBody) {
        try {
            IClientProfile profile = DefaultProfile.getProfile(config.region(),
                    config.accessKeyId(), config.accessKeySecret());
            IAcsClient client = new DefaultAcsClient(profile);

            SingleSendMailRequest request = new SingleSendMailRequest();
            request.setAccountName(config.accountName());
            request.setAddressType(1);
            request.setReplyToAddress(config.replyToAddress() != null && !config.replyToAddress().isBlank());
            request.setToAddress(toEmail);
            request.setSubject(subject);
            request.setHtmlBody(htmlBody);
            request.setFromAlias(config.fromAlias());

            SingleSendMailResponse response = client.getAcsResponse(request);
            log.info("邮件发送成功 channel=ALIYUN to={} subject={} requestId={}",
                    maskEmail(toEmail), subject, response.getRequestId());
            return true;
        } catch (Exception e) {
            log.error("邮件发送失败 channel=ALIYUN to={} subject={} : {}", maskEmail(toEmail), subject, e.toString());
            return false;
        }
    }

    static String maskEmail(String email) {
        if (email == null || email.isBlank()) return "";
        int at = email.indexOf('@');
        if (at <= 1) return email.charAt(0) + "***" + (at > 0 ? email.substring(at) : "");
        return email.charAt(0) + "***" + email.substring(at);
    }
}
