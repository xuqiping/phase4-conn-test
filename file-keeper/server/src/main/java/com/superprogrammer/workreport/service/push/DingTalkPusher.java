package com.superprogrammer.workreport.service.push;

import com.superprogrammer.workreport.entity.PushTarget;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class DingTalkPusher implements PushService {

    private final RestTemplate restTemplate;

    @Override
    public boolean supports(Platform platform) {
        return platform == Platform.DINGTALK;
    }

    @Override
    public PushResult push(PushPayload payload, PushTarget target, String decryptedCredential) {
        try {
            String accessToken = target.getTargetId();
            String secret = decryptedCredential;

            long timestamp = System.currentTimeMillis();
            String sign = secret != null && !secret.isBlank() ? sign(timestamp, secret) : null;

            StringBuilder url = new StringBuilder("https://oapi.dingtalk.com/robot/send?access_token=")
                    .append(URLEncoder.encode(accessToken, StandardCharsets.UTF_8));
            if (sign != null) {
                url.append("×tamp=").append(timestamp)
                   .append("&sign=").append(URLEncoder.encode(sign, StandardCharsets.UTF_8));
            }

            Map<String, Object> requestBody = Map.of(
                    "msgtype", "text",
                    "text", Map.of("content", buildMessage(payload))
            );

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            ResponseEntity<String> response = restTemplate.postForEntity(
                    url.toString(),
                    new HttpEntity<>(requestBody, headers),
                    String.class
            );

            if (response.getStatusCode().is2xxSuccessful()) {
                return new PushResult(true, "钉钉推送成功", response.getBody());
            } else {
                return new PushResult(false, "钉钉推送失败: " + response.getStatusCode(), response.getBody());
            }
        } catch (Exception e) {
            log.error("钉钉推送异常", e);
            return new PushResult(false, "钉钉推送异常: " + e.getMessage(), null);
        }
    }

    private String buildMessage(PushPayload payload) {
        if (payload.title() == null || payload.title().isBlank()) {
            return payload.content();
        }
        return payload.title() + "\n\n" + payload.content();
    }

    private String sign(long timestamp, String secret) throws Exception {
        String stringToSign = timestamp + "\n" + secret;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] signData = mac.doFinal(stringToSign.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(signData);
    }
}
