package com.superprogrammer.workreport.service.push;

import com.superprogrammer.common.JsonUtils;
import com.superprogrammer.workreport.entity.ReportPushTarget;
import com.superprogrammer.workreport.service.CredentialEncryptor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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
public class FeishuPusher implements PushService {

    private final RestTemplate restTemplate;
    private final CredentialEncryptor credentialEncryptor;

    @Value("${work-report.feishu.api-base:https://open.feishu.cn/open-apis}")
    private String feishuApiBase;

    @Override
    public boolean supports(Platform platform) {
        return platform == Platform.FEISHU;
    }

    @Override
    public PushResult push(PushPayload payload, ReportPushTarget target) {
        try {
            log.info("[FeishuPusher] 开始推送 targetId={} targetType={}", target.getTargetId(), target.getTargetType());
            FeishuCredential credential = parseCredential(target.getCredential());
            log.info("[FeishuPusher] credential 解析成功 appId={}", credential.getAppId());
            log.info("[FeishuPusher] 正在获取 tenant_access_token...");
            String tenantAccessToken = getTenantAccessToken(credential);
            log.info("[FeishuPusher] 获取 tenant_access_token 成功");

            String url = feishuApiBase + "/message/v4/send/";
            Object requestBody;

            if ("GROUP".equalsIgnoreCase(target.getTargetType())) {
                requestBody = Map.of(
                        "chat_id", target.getTargetId(),
                        "msg_type", "text",
                        "content", Map.of("text", buildMessage(payload))
                );
            } else {
                requestBody = Map.of(
                        "open_id", target.getTargetId(),
                        "msg_type", "text",
                        "content", Map.of("text", buildMessage(payload))
                );
            }

            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(tenantAccessToken);
            headers.setContentType(MediaType.APPLICATION_JSON);

            log.info("[FeishuPusher] 正在发送消息 url={} targetId={}", url, target.getTargetId());
            ResponseEntity<String> response = restTemplate.postForEntity(
                    url,
                    new HttpEntity<>(requestBody, headers),
                    String.class
            );
            log.info("[FeishuPusher] 飞书响应 status={} body={}", response.getStatusCode(), response.getBody());

            if (response.getStatusCode().is2xxSuccessful()) {
                return new PushResult(true, "推送成功", response.getBody());
            } else {
                return new PushResult(false, "推送失败: " + response.getStatusCode(), response.getBody());
            }
        } catch (Exception e) {
            log.error("[FeishuPusher] 飞书推送异常", e);
            return new PushResult(false, "推送异常: " + e.getMessage(), null);
        }
    }

    @SuppressWarnings("unchecked")
    private String getTenantAccessToken(FeishuCredential credential) {
        String url = feishuApiBase + "/auth/v3/tenant_access_token/internal/";
        log.info("[FeishuPusher] 请求 tenant_access_token url={}", url);
        Map<String, String> body = Map.of(
                "app_id", credential.getAppId(),
                "app_secret", credential.getAppSecret()
        );
        ResponseEntity<Map> response = restTemplate.postForEntity(url, body, Map.class);
        log.info("[FeishuPusher] tenant_access_token 响应 status={} body={}", response.getStatusCode(), response.getBody());
        Map<String, Object> responseBody = response.getBody();
        if (responseBody == null || responseBody.get("tenant_access_token") == null) {
            throw new RuntimeException("获取飞书 tenant_access_token 失败: " + responseBody);
        }
        return responseBody.get("tenant_access_token").toString();
    }

    private String buildMessage(PushPayload payload) {
        if (payload.title() == null || payload.title().isBlank()) {
            return payload.content();
        }
        return payload.title() + "\n\n" + payload.content();
    }

    private FeishuCredential parseCredential(String encryptedCredential) {
        String json = credentialEncryptor.decrypt(encryptedCredential);
        return JsonUtils.parse(json, FeishuCredential.class);
    }
}
