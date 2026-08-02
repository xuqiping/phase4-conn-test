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
public class SlackPusher implements PushService {

    private final RestTemplate restTemplate;

    @Override
    public boolean supports(Platform platform) {
        return platform == Platform.SLACK;
    }

    @Override
    public PushResult push(PushPayload payload, PushTarget target, String decryptedCredential) {
        try {
            String webhookUrl = target.getTargetId();

            Map<String, Object> requestBody = Map.of(
                    "text", buildMessage(payload)
            );

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            ResponseEntity<String> response = restTemplate.postForEntity(
                    webhookUrl,
                    new HttpEntity<>(requestBody, headers),
                    String.class
            );

            if (response.getStatusCode().is2xxSuccessful()) {
                return new PushResult(true, "Slack 推送成功", response.getBody());
            } else {
                return new PushResult(false, "Slack 推送失败: " + response.getStatusCode(), response.getBody());
            }
        } catch (Exception e) {
            log.error("Slack 推送异常", e);
            return new PushResult(false, "Slack 推送异常: " + e.getMessage(), null);
        }
    }

    private String buildMessage(PushPayload payload) {
        if (payload.title() == null || payload.title().isBlank()) {
            return payload.content();
        }
        return "*" + payload.title() + "*\n\n" + payload.content();
    }
}
