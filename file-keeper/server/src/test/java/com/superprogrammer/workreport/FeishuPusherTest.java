package com.superprogrammer.workreport;

import com.superprogrammer.workreport.entity.PushTarget;
import com.superprogrammer.workreport.service.CredentialEncryptor;
import com.superprogrammer.workreport.service.push.FeishuPusher;
import com.superprogrammer.workreport.service.push.Platform;
import com.superprogrammer.workreport.service.push.PushPayload;
import com.superprogrammer.workreport.service.push.PushResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FeishuPusherTest {

    private RestTemplate restTemplate;
    private CredentialEncryptor credentialEncryptor;
    private FeishuPusher feishuPusher;

    @BeforeEach
    void setUp() {
        restTemplate = mock(RestTemplate.class);
        credentialEncryptor = new CredentialEncryptor();
        feishuPusher = new FeishuPusher(restTemplate, credentialEncryptor);
        ReflectionTestUtils.setField(feishuPusher, "feishuApiBase", "https://open.feishu.cn/open-apis");
    }

    @Test
    void supportsFeishu() {
        assertTrue(feishuPusher.supports(Platform.FEISHU));
        assertFalse(feishuPusher.supports(Platform.DINGTALK));
    }

    @Test
    void pushToGroupSuccessfully() {
        PushTarget target = new PushTarget();
        target.setPlatform("FEISHU");
        target.setTargetType("GROUP");
        target.setTargetId("chat123");

        String credential = "{\"appId\":\"app123\",\"appSecret\":\"secret\"}";
        PushPayload payload = new PushPayload("日报", "今日工作");

        when(restTemplate.postForEntity(eq("https://open.feishu.cn/open-apis/auth/v3/tenant_access_token/internal/"),
                any(), eq(Map.class)))
                .thenReturn(ResponseEntity.ok(Map.of("tenant_access_token", "token123", "code", 0)));

        when(restTemplate.postForEntity(eq("https://open.feishu.cn/open-apis/message/v4/send/"),
                any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok("ok"));

        PushResult result = feishuPusher.push(payload, target, credential);

        assertTrue(result.success());
        assertEquals("推送成功", result.message());

        ArgumentCaptor<HttpEntity> captor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).postForEntity(eq("https://open.feishu.cn/open-apis/message/v4/send/"), captor.capture(), eq(String.class));
        String body = captor.getValue().getBody().toString();
        assertTrue(body.contains("chat_id=chat123"));
    }

    @Test
    void pushFailureWhenTokenRequestFails() {
        PushTarget target = new PushTarget();
        target.setPlatform("FEISHU");
        target.setTargetType("USER");
        target.setTargetId("open123");

        String credential = "{\"appId\":\"app123\",\"appSecret\":\"secret\"}";
        PushPayload payload = new PushPayload("日报", "今日工作");

        when(restTemplate.postForEntity(anyString(), any(), eq(Map.class)))
                .thenReturn(ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("msg", "invalid app")));

        PushResult result = feishuPusher.push(payload, target, credential);

        assertFalse(result.success());
        assertTrue(result.message().contains("推送异常"));
    }
}
