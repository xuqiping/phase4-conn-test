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

import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class WeComPusher implements PushService {

    private final RestTemplate restTemplate;

    @Override
    public boolean supports(Platform platform) {
        return platform == Platform.WECHAT_WORK;
    }

    @Override
    public PushResult push(PushPayload payload, PushTarget target, String decryptedCredential) {
        try {
            String key = target.getTargetId();
            String url = "https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=" + key;

            Map<String, Object> requestBody = Map.of(
                    "msgtype", "text",
                    "text", Map.of("content", buildMessage(payload))
            );

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            ResponseEntity<String> response = restTemplate.postForEntity(
                    url,
                    new HttpEntity<>(requestBody, headers),
                    String.class
            );

            if (response.getStatusCode().is2xxSuccessful()) {
                return new PushResult(true, "企业微信推送成功", response.getBody());
            } else {
                return new PushResult(false, "企业微信推送失败: " + response.getStatusCode(), response.getBody());
            }
        } catch (Exception e) {
            log.error("企业微信推送异常", e);
            return new PushResult(false, "企业微信推送异常: " + e.getMessage(), null);
        }
    }

    private String buildMessage(PushPayload payload) {
        if (payload.title() == null || payload.title().isBlank()) {
            return payload.content();
        }
        return payload.title() + "\n\n" + payload.content();
    }
}
